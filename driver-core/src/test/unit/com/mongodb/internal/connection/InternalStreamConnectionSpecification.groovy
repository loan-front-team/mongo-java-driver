/*
 * Copyright 2008-present MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb.internal.connection

import com.mongodb.MongoCommandException
import com.mongodb.MongoInternalException
import com.mongodb.MongoNamespace
import com.mongodb.MongoSocketClosedException
import com.mongodb.MongoSocketException
import com.mongodb.MongoSocketReadException
import com.mongodb.MongoSocketWriteException
import com.mongodb.ReadConcern
import com.mongodb.ServerAddress
import com.mongodb.async.FutureResultCallback
import com.mongodb.connection.AsyncCompletionHandler
import com.mongodb.connection.ClusterId
import com.mongodb.connection.ConnectionDescription
import com.mongodb.connection.ConnectionId
import com.mongodb.connection.ServerConnectionState
import com.mongodb.connection.ServerDescription
import com.mongodb.connection.ServerId
import com.mongodb.connection.ServerType
import com.mongodb.connection.Stream
import com.mongodb.connection.StreamFactory
import com.mongodb.event.CommandFailedEvent
import com.mongodb.event.CommandStartedEvent
import com.mongodb.event.CommandSucceededEvent
import com.mongodb.internal.IgnorableRequestContext
import com.mongodb.internal.session.SessionContext
import com.mongodb.internal.validator.NoOpFieldNameValidator
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.BsonReader
import org.bson.BsonString
import org.bson.ByteBuf
import org.bson.ByteBufNIO
import org.bson.codecs.BsonDocumentCodec
import org.bson.codecs.DecoderContext
import org.bson.codecs.configuration.CodecConfigurationException
import spock.lang.Specification

import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import static com.mongodb.ReadPreference.primary
import static com.mongodb.connection.ClusterConnectionMode.MULTIPLE
import static com.mongodb.connection.ClusterConnectionMode.SINGLE
import static com.mongodb.connection.ConnectionDescription.getDefaultMaxMessageSize
import static com.mongodb.connection.ConnectionDescription.getDefaultMaxWriteBatchSize
import static com.mongodb.connection.ServerDescription.getDefaultMaxDocumentSize
import static com.mongodb.internal.connection.MessageHelper.LEGACY_HELLO
import static com.mongodb.internal.connection.MessageHelper.LEGACY_HELLO_LOWER
import static com.mongodb.internal.operation.ServerVersionHelper.THREE_DOT_SIX_WIRE_VERSION
import static java.util.concurrent.TimeUnit.NANOSECONDS
import static java.util.concurrent.TimeUnit.SECONDS

@SuppressWarnings(['UnusedVariable'])
class InternalStreamConnectionSpecification extends Specification {
    private static final ServerId SERVER_ID = new ServerId(new ClusterId(), new ServerAddress())

    def cmdNamespace = new MongoNamespace('admin.$cmd')
    def fieldNameValidator = new NoOpFieldNameValidator()
    def helper = new StreamHelper()
    def serverAddress = new ServerAddress()
    def connectionId = new ConnectionId(SERVER_ID, 1, 1)
    def commandListener = new TestCommandListener()
    def messageSettings = MessageSettings.builder().maxWireVersion(THREE_DOT_SIX_WIRE_VERSION).build()

    def connectionDescription = new ConnectionDescription(connectionId, 3,
            ServerType.STANDALONE, getDefaultMaxWriteBatchSize(), getDefaultMaxDocumentSize(), getDefaultMaxMessageSize(), [])
    def serverDescription = ServerDescription.builder()
            .ok(true)
            .state(ServerConnectionState.CONNECTED)
            .type(ServerType.STANDALONE)
            .address(serverAddress)
            .build()
    def internalConnectionInitializationDescription =
            new InternalConnectionInitializationDescription(connectionDescription, serverDescription)
    def stream = Mock(Stream) {
        openAsync(_) >> { it[0].completed(null) }
    }
    def streamFactory = Mock(StreamFactory) {
        create(_) >> { stream }
    }
    def initializer = Mock(InternalConnectionInitializer) {
        startHandshake(_) >> { internalConnectionInitializationDescription }
        finishHandshake(_, _) >> { internalConnectionInitializationDescription }
        startHandshakeAsync(_, _) >> { it[1].onResult(internalConnectionInitializationDescription, null) }
        finishHandshakeAsync(_, _, _) >> { it[2].onResult(internalConnectionInitializationDescription, null) }
    }

    def getConnection() {
        new InternalStreamConnection(SINGLE, SERVER_ID, new TestConnectionGenerationSupplier(), streamFactory, [], commandListener,
                initializer, null)
    }

    def getOpenedConnection() {
        def connection = getConnection()
        connection.open()
        connection
    }

    def 'should change the description when opened'() {
        when:
        def connection = getConnection()

        then:
        connection.getDescription().getServerType() == ServerType.UNKNOWN
        connection.getDescription().getConnectionId().getServerValue() == null
        connection.getInitialServerDescription() == ServerDescription.builder()
                .address(serverAddress)
                .type(ServerType.UNKNOWN)
                .state(ServerConnectionState.CONNECTING)
                .lastUpdateTimeNanos(connection.getInitialServerDescription().getLastUpdateTime(NANOSECONDS))
                .build()
        when:
        connection.open()

        then:
        connection.opened()
        connection.getDescription().getServerType() == ServerType.STANDALONE
        connection.getDescription().getConnectionId().getServerValue() == 1
        connection.getDescription() == connectionDescription
        connection.getInitialServerDescription() == serverDescription
    }


    def 'should change the description when opened asynchronously'() {
        when:
        def connection = getConnection()
        def futureResultCallback = new FutureResultCallback<Void>()

        then:
        connection.getDescription().getServerType() == ServerType.UNKNOWN
        connection.getDescription().getConnectionId().getServerValue() == null
        connection.getInitialServerDescription() == ServerDescription.builder()
                .address(serverAddress)
                .type(ServerType.UNKNOWN)
                .state(ServerConnectionState.CONNECTING)
                .lastUpdateTimeNanos(connection.getInitialServerDescription().getLastUpdateTime(NANOSECONDS))
                .build()

        when:
        connection.openAsync(futureResultCallback)
        futureResultCallback.get()

        then:
        connection.opened()
        connection.getDescription() == connectionDescription
        connection.getInitialServerDescription() == serverDescription
    }

    def 'should close the stream when initialization throws an exception'() {
        given:
        def failedInitializer = Mock(InternalConnectionInitializer) {
            startHandshake(_) >> { throw new MongoInternalException('Something went wrong') }
        }
        def connection = new InternalStreamConnection(SINGLE, SERVER_ID, new TestConnectionGenerationSupplier(), streamFactory, [], null,
                failedInitializer, null)

        when:
        connection.open()

        then:
        thrown MongoInternalException
        connection.isClosed()
    }


    def 'should close the stream when initialization throws an exception asynchronously'() {
        given:
        def failedInitializer = Mock(InternalConnectionInitializer) {
            startHandshakeAsync(_, _) >> { it[1].onResult(null, new MongoInternalException('Something went wrong')) }
        }
        def connection = new InternalStreamConnection(SINGLE, SERVER_ID, new TestConnectionGenerationSupplier(), streamFactory, [], null,
                failedInitializer, null)

        when:
        def futureResultCallback = new FutureResultCallback<Void>()
        connection.openAsync(futureResultCallback)
        futureResultCallback.get()

        then:
        thrown MongoInternalException
        connection.isClosed()
    }

    def 'should close the stream when writing a message throws an exception'() {
        given:
        stream.write(_) >> { throw new IOException('Something went wrong') }

        def connection = getOpenedConnection()
        def (buffers1, messageId1) = helper.hello()
        def (buffers2, messageId2) = helper.hello()

        when:
        connection.sendMessage(buffers1, messageId1)

        then:
        connection.isClosed()
        thrown MongoSocketWriteException

        when:
        connection.sendMessage(buffers2, messageId2)

        then:
        thrown MongoSocketClosedException
    }


    def 'should close the stream when writing a message throws an exception asynchronously'() {
        given:
        def (buffers1, messageId1, sndCallbck1, rcvdCallbck1) = helper.helloAsync()
        def (buffers2, messageId2, sndCallbck2, rcvdCallbck2) = helper.helloAsync()
        int seen = 0

        stream.writeAsync(_, _) >> { List<ByteBuf> buffers, AsyncCompletionHandler<Void> callback ->
            if (seen == 0) {
                seen += 1
                return callback.failed(new IOException('Something went wrong'))
            }
            callback.completed(null)
        }

        def connection = getOpenedConnection()

        when:
        connection.sendMessageAsync(buffers1, messageId1, sndCallbck1)
        sndCallbck1.get(10, SECONDS)

        then:
        thrown MongoSocketWriteException
        connection.isClosed()

        when:
        connection.sendMessageAsync(buffers2, messageId2, sndCallbck2)
        sndCallbck2.get(10, SECONDS)

        then:
        thrown MongoSocketClosedException
    }

    def 'should close the stream when reading the message header throws an exception'() {
        given:
        stream.read(16, 0) >> { throw new IOException('Something went wrong') }

        def connection = getOpenedConnection()
        def (buffers1, messageId1) = helper.hello()
        def (buffers2, messageId2) = helper.hello()

        when:
        connection.sendMessage(buffers1, messageId1)
        connection.sendMessage(buffers2, messageId2)
        connection.receiveMessage(messageId1)

        then:
        connection.isClosed()
        thrown MongoSocketReadException

        when:
        connection.receiveMessage(messageId2)

        then:
        thrown MongoSocketClosedException
    }

    def 'should throw MongoInternalException when reply header message length > max message length'() {
        given:
        stream.read(36, 0) >> { helper.headerWithMessageSizeGreaterThanMax(1) }

        def connection = getOpenedConnection()

        when:
        connection.receiveMessage(1)

        then:
        thrown(MongoInternalException)
        connection.isClosed()
    }

    def 'should throw MongoInternalException when reply header message length > max message length asynchronously'() {
        given:
        stream.readAsync(16, _) >> { int numBytes, AsyncCompletionHandler<ByteBuf> handler ->
            handler.completed(helper.headerWithMessageSizeGreaterThanMax(1, connectionDescription.maxMessageSize))
        }

        def connection = getOpenedConnection()
        def callback = new FutureResultCallback()

        when:
        connection.receiveMessageAsync(1, callback)
        callback.get()

        then:
        thrown(MongoInternalException)
        connection.isClosed()
    }


    def 'should close the stream when reading the message header throws an exception asynchronously'() {
        given:
        int seen = 0
        def (buffers1, messageId1, sndCallbck1, rcvdCallbck1) = helper.helloAsync()
        def (buffers2, messageId2, sndCallbck2, rcvdCallbck2) = helper.helloAsync()
        def headers = helper.generateHeaders([messageId1, messageId2])

        stream.writeAsync(_, _) >> { List<ByteBuf> buffers, AsyncCompletionHandler<Void> callback ->
            callback.completed(null)
        }
        stream.readAsync(16, _) >> { int numBytes, AsyncCompletionHandler<ByteBuf> handler ->
            if (seen == 0) {
                seen += 1
                return handler.failed(new IOException('Something went wrong'))
            }
            handler.completed(headers.pop())
        }
        stream.readAsync(94, _) >> { int numBytes, AsyncCompletionHandler<ByteBuf> handler ->
            handler.completed(helper.defaultBody())
        }
        def connection = getOpenedConnection()

        when:
        connection.sendMessageAsync(buffers1, messageId1, sndCallbck1)
        connection.sendMessageAsync(buffers2, messageId2, sndCallbck2)
        connection.receiveMessageAsync(messageId1, rcvdCallbck1)
        connection.receiveMessageAsync(messageId2, rcvdCallbck2)
        rcvdCallbck1.get(1, SECONDS)

        then:
        thrown MongoSocketReadException
        connection.isClosed()

        when:
        rcvdCallbck2.get(1, SECONDS)

        then:
        thrown MongoSocketClosedException
    }

    def 'should close the stream when reading the message body throws an exception'() {
        given:
        stream.read(16, 0) >> helper.defaultMessageHeader(1)
        stream.read(90, 0) >> { throw new IOException('Something went wrong') }

        def connection = getOpenedConnection()

        when:
        connection.receiveMessage(1)

        then:
        connection.isClosed()
        thrown MongoSocketReadException

        when:
        connection.receiveMessage(1)

        then:
        thrown MongoSocketClosedException
    }


    def 'should close the stream when reading the message body throws an exception asynchronously'() {
        given:
        def (buffers1, messageId1, sndCallbck1, rcvdCallbck1) = helper.helloAsync()
        def (buffers2, messageId2, sndCallbck2, rcvdCallbck2) = helper.helloAsync()
        def headers = helper.generateHeaders([messageId1, messageId2])

        stream.writeAsync(_, _) >> { List<ByteBuf> buffers, AsyncCompletionHandler<Void> callback ->
            callback.completed(null)
        }
        stream.readAsync(16, _) >> { int numBytes, AsyncCompletionHandler<ByteBuf> handler ->
            handler.completed(headers.remove(0))
        }
        stream.readAsync(_, _) >> { int numBytes, AsyncCompletionHandler<ByteBuf> handler ->
            handler.failed(new IOException('Something went wrong'))
        }
        def connection = getOpenedConnection()

        when:
        connection.sendMessageAsync(buffers1, messageId1, sndCallbck1)
        connection.sendMessageAsync(buffers2, messageId2, sndCallbck2)
        connection.receiveMessageAsync(messageId1, rcvdCallbck1)
        rcvdCallbck1.get(1, SECONDS)

        then:
        thrown MongoSocketReadException
        connection.isClosed()

        when:
        connection.receiveMessageAsync(messageId2, rcvdCallbck2)
        rcvdCallbck2.get(1, SECONDS)

        then:
        thrown MongoSocketClosedException
    }

    def 'should not close the stream on a command exception'() {
        given:
        def connection = getOpenedConnection()
        def pingCommandDocument = new BsonDocument('ping', new BsonInt32(1))
        def commandMessage = new CommandMessage(cmdNamespace, pingCommandDocument, fieldNameValidator, primary(), messageSettings, MULTIPLE,
                null)
        def response = '{ok : 0, errmsg : "failed"}'
        stream.getBuffer(1024) >> { new ByteBufNIO(ByteBuffer.wrap(new byte[1024])) }
        stream.read(16, 0) >> helper.messageHeader(commandMessage.getId(), response)
        stream.read(_, 0) >> helper.reply(response)

        when:
        connection.sendAndReceive(commandMessage, new BsonDocumentCodec(), NoOpSessionContext.INSTANCE, IgnorableRequestContext.INSTANCE,
                new OperationContext())

        then:
        thrown(MongoCommandException)
        !connection.isClosed()
    }

    def 'should not close the stream on an asynchronous command exception'() {
        given:
        def connection = getOpenedConnection()
        def pingCommandDocument = new BsonDocument('ping', new BsonInt32(1))
        def commandMessage = new CommandMessage(cmdNamespace, pingCommandDocument, fieldNameValidator, primary(), messageSettings, MULTIPLE,
                null)
        def callback = new FutureResultCallback()
        def response = '{ok : 0, errmsg : "failed"}'

        stream.getBuffer(1024) >> { new ByteBufNIO(ByteBuffer.wrap(new byte[1024])) }
        stream.writeAsync(_, _) >> { buffers, handler ->
            handler.completed(null)
        }
        stream.readAsync(16, _) >> { numBytes, handler ->
            handler.completed(helper.defaultMessageHeader(commandMessage.getId()))
        }
        stream.readAsync(_, _) >> { numBytes, handler ->
            handler.completed(helper.reply(response))
        }

        when:
        connection.sendAndReceiveAsync(commandMessage, new BsonDocumentCodec(), NoOpSessionContext.INSTANCE,
                IgnorableRequestContext.INSTANCE, new OperationContext(), callback)
        callback.get()

        then:
        thrown(MongoCommandException)
        !connection.isClosed()
    }

    def 'should notify all asynchronous writers of an exception'() {
        given:
        int numberOfOperations = 3
        ExecutorService streamPool = Executors.newFixedThreadPool(1)

        def messages = (1..numberOfOperations).collect { helper.helloAsync() }

        def streamLatch = new CountDownLatch(1)
        stream.writeAsync(_, _) >> { List<ByteBuf> buffers, AsyncCompletionHandler<Void> callback ->
            streamPool.submit {
                streamLatch.await()
                callback.failed(new IOException())
            }
        }

        when:
        def connection = getOpenedConnection()
        def callbacks = []
        (1..numberOfOperations).each { n ->
            def (buffers, messageId, sndCallbck, rcvdCallbck) = messages.pop()
            connection.sendMessageAsync(buffers, messageId, sndCallbck)
            callbacks.add(sndCallbck)
        }
        streamLatch.countDown()

        then:
        expectException(callbacks.pop())
        expectException(callbacks.pop())
        expectException(callbacks.pop())

        cleanup:
        streamPool.shutdown()
    }

    def 'should send events for successful command'() {
        given:
        def connection = getOpenedConnection()
        def pingCommandDocument = new BsonDocument('ping', new BsonInt32(1))
        def commandMessage = new CommandMessage(cmdNamespace, pingCommandDocument, fieldNameValidator, primary(), messageSettings, MULTIPLE,
                null)
        stream.getBuffer(1024) >> { new ByteBufNIO(ByteBuffer.wrap(new byte[1024])) }
        stream.read(16, 0) >> helper.defaultMessageHeader(commandMessage.getId())
        stream.read(90, 0) >> helper.defaultReply()

        when:
        connection.sendAndReceive(commandMessage, new BsonDocumentCodec(), NoOpSessionContext.INSTANCE, IgnorableRequestContext.INSTANCE,
                new OperationContext())

        then:
        commandListener.eventsWereDelivered([
                new CommandStartedEvent(null, 1, 1, connection.getDescription(), 'admin', 'ping',
                        pingCommandDocument.append('$db', new BsonString('admin'))),
                new CommandSucceededEvent(null, 1, 1, connection.getDescription(), 'ping',
                        new BsonDocument('ok', new BsonInt32(1)), 1000)])
    }

    def 'should send events for successful command with decoding error'() {
        given:
        def connection = getOpenedConnection()
        def pingCommandDocument = new BsonDocument('ping', new BsonInt32(1))
        def commandMessage = new CommandMessage(cmdNamespace, pingCommandDocument, fieldNameValidator, primary(), messageSettings, MULTIPLE,
                null)
        stream.getBuffer(1024) >> { new ByteBufNIO(ByteBuffer.wrap(new byte[1024])) }
        stream.read(16, 0) >> helper.defaultMessageHeader(commandMessage.getId())
        stream.read(90, 0) >> helper.defaultReply()

        when:
        connection.sendAndReceive(commandMessage, {
            BsonReader reader, DecoderContext decoderContext -> throw new CodecConfigurationException('')
        }, NoOpSessionContext.INSTANCE, IgnorableRequestContext.INSTANCE, new OperationContext())

        then:
        thrown(CodecConfigurationException)
        commandListener.eventsWereDelivered([
                new CommandStartedEvent(null, 1, 1, connection.getDescription(), 'admin', 'ping',
                        pingCommandDocument.append('$db', new BsonString('admin'))),
                new CommandSucceededEvent(null, 1, 1, connection.getDescription(), 'ping',
                        new BsonDocument('ok', new BsonInt32(1)), 1000)])
    }

    def 'should extract cluster and operation time into session context'() {
        given:
        def connection = getOpenedConnection()
        def pingCommandDocument = new BsonDocument('ping', new BsonInt32(1))
        def commandMessage = new CommandMessage(cmdNamespace, pingCommandDocument, fieldNameValidator, primary(), messageSettings, MULTIPLE,
                null)
        def response = '''{
                            ok : 1,
                            operationTime : { $timestamp : { "t" : 40, "i" : 20 } },
                            $clusterTime :  { clusterTime : { $timestamp : { "t" : 42, "i" : 21 } } }
                          }'''
        stream.getBuffer(1024) >> { new ByteBufNIO(ByteBuffer.wrap(new byte[1024])) }
        stream.read(16, 0) >> helper.defaultMessageHeader(commandMessage.getId())
        stream.read(_, 0) >> helper.reply(response)
        def sessionContext = Mock(SessionContext) {
            1 * advanceOperationTime(BsonDocument.parse(response).getTimestamp('operationTime'))
            1 * advanceClusterTime(BsonDocument.parse(response).getDocument('$clusterTime'))
            getReadConcern() >> ReadConcern.DEFAULT
        }

        when:
        connection.sendAndReceive(commandMessage, new BsonDocumentCodec(), sessionContext, IgnorableRequestContext.INSTANCE,
                new OperationContext())

        then:
        true
    }

    def 'should extract cluster and operation time into session context asynchronously'() {
        given:
        def connection = getOpenedConnection()
        def pingCommandDocument = new BsonDocument('ping', new BsonInt32(1))
        def commandMessage = new CommandMessage(cmdNamespace, pingCommandDocument, fieldNameValidator, primary(), messageSettings, MULTIPLE,
                null)
        def callback = new FutureResultCallback()
        def response = '''{
                            ok : 1,
                            operationTime : { $timestamp : { "t" : 40, "i" : 20 } },
                            $clusterTime :  { clusterTime : { $timestamp : { "t" : 42, "i" : 21 } } }
                          }'''
        stream.getBuffer(1024) >> { new ByteBufNIO(ByteBuffer.wrap(new byte[1024])) }
        stream.writeAsync(_, _) >> { buffers, handler ->
            handler.completed(null)
        }
        stream.readAsync(16, _) >> { numBytes, handler ->
            handler.completed(helper.defaultMessageHeader(commandMessage.getId()))
        }
        stream.readAsync(_, _) >> { numBytes, handler ->
            handler.completed(helper.reply(response))
        }
        def sessionContext = Mock(SessionContext) {
            1 * advanceOperationTime(BsonDocument.parse(response).getTimestamp('operationTime'))
            1 * advanceClusterTime(BsonDocument.parse(response).getDocument('$clusterTime'))
            getReadConcern() >> ReadConcern.DEFAULT
        }

        when:
        connection.sendAndReceiveAsync(commandMessage, new BsonDocumentCodec(), sessionContext, IgnorableRequestContext.INSTANCE,
                new OperationContext(), callback)
        callback.get()

        then:
        true
    }

    def 'should send events for command failure with exception writing message'() {
        given:
        def connection = getOpenedConnection()
        def pingCommandDocument = new BsonDocument('ping', new BsonInt32(1))
        def commandMessage = new CommandMessage(cmdNamespace, pingCommandDocument, fieldNameValidator, primary(), messageSettings, MULTIPLE,
                null)
        stream.getBuffer(1024) >> { new ByteBufNIO(ByteBuffer.wrap(new byte[1024])) }
        stream.write(_) >> { throw new MongoSocketWriteException('Failed to write', serverAddress, new IOException()) }

        when:
        connection.sendAndReceive(commandMessage, new BsonDocumentCodec(), NoOpSessionContext.INSTANCE, IgnorableRequestContext.INSTANCE,
                new OperationContext())

        then:
        def e = thrown(MongoSocketWriteException)
        commandListener.eventsWereDelivered([
                new CommandStartedEvent(null, 1, 1, connection.getDescription(), 'admin', 'ping',
                        pingCommandDocument.append('$db', new BsonString('admin'))),
                new CommandFailedEvent(null, 1, 1, connection.getDescription(), 'ping', 0, e)])
    }

    def 'should send events for command failure with exception reading header'() {
        given:
        def connection = getOpenedConnection()
        def pingCommandDocument = new BsonDocument('ping', new BsonInt32(1))
        def commandMessage = new CommandMessage(cmdNamespace, pingCommandDocument, fieldNameValidator, primary(), messageSettings, MULTIPLE,
                null)
        stream.getBuffer(1024) >> { new ByteBufNIO(ByteBuffer.wrap(new byte[1024])) }
        stream.read(16, 0) >> { throw new MongoSocketReadException('Failed to read', serverAddress) }

        when:
        connection.sendAndReceive(commandMessage, new BsonDocumentCodec(), NoOpSessionContext.INSTANCE, IgnorableRequestContext.INSTANCE,
                new OperationContext())

        then:
        def e = thrown(MongoSocketReadException)
        commandListener.eventsWereDelivered([
                new CommandStartedEvent(null, 1, 1, connection.getDescription(), 'admin', 'ping',
                        pingCommandDocument.append('$db', new BsonString('admin'))),
                new CommandFailedEvent(null, 1, 1, connection.getDescription(), 'ping', 0, e)])
    }

    def 'should send events for command failure with exception reading body'() {
        given:
        def connection = getOpenedConnection()
        def pingCommandDocument = new BsonDocument('ping', new BsonInt32(1))
        def commandMessage = new CommandMessage(cmdNamespace, pingCommandDocument, fieldNameValidator, primary(), messageSettings, MULTIPLE,
                null)
        stream.getBuffer(1024) >> { new ByteBufNIO(ByteBuffer.wrap(new byte[1024])) }
        stream.read(16, 0) >> helper.defaultMessageHeader(commandMessage.getId())
        stream.read(90, 0) >> { throw new MongoSocketReadException('Failed to read', serverAddress) }

        when:
        connection.sendAndReceive(commandMessage, new BsonDocumentCodec(), NoOpSessionContext.INSTANCE, IgnorableRequestContext.INSTANCE,
                new OperationContext())

        then:
        def e = thrown(MongoSocketException)
        commandListener.eventsWereDelivered([
                new CommandStartedEvent(null, 1, 1, connection.getDescription(), 'admin', 'ping',
                        pingCommandDocument.append('$db', new BsonString('admin'))),
                new CommandFailedEvent(null, 1, 1, connection.getDescription(), 'ping', 0, e)])
    }

    def 'should send events for command failure with exception from failed command'() {
        given:
        def connection = getOpenedConnection()
        def pingCommandDocument = new BsonDocument('ping', new BsonInt32(1))
        def commandMessage = new CommandMessage(cmdNamespace, pingCommandDocument, fieldNameValidator, primary(), messageSettings, MULTIPLE,
                null)
        def response = '{ok : 0, errmsg : "failed"}'
        stream.getBuffer(1024) >> { new ByteBufNIO(ByteBuffer.wrap(new byte[1024])) }
        stream.read(16, 0) >> helper.messageHeader(commandMessage.getId(), response)
        stream.read(_, 0) >> helper.reply(response)

        when:
        connection.sendAndReceive(commandMessage, new BsonDocumentCodec(), NoOpSessionContext.INSTANCE, IgnorableRequestContext.INSTANCE,
                new OperationContext())

        then:
        def e = thrown(MongoCommandException)
        commandListener.eventsWereDelivered([
                new CommandStartedEvent(null, 1, 1, connection.getDescription(), 'admin', 'ping',
                        pingCommandDocument.append('$db', new BsonString('admin'))),
                new CommandFailedEvent(null, 1, 1, connection.getDescription(), 'ping', 0, e)])
    }

    def 'should send events with elided command and response in successful security-sensitive commands'() {
        given:
        def securitySensitiveCommandName = securitySensitiveCommand.keySet().iterator().next()
        def connection = getOpenedConnection()
        def commandMessage = new CommandMessage(cmdNamespace, securitySensitiveCommand, fieldNameValidator, primary(), messageSettings,
                MULTIPLE, null)
        stream.getBuffer(1024) >> { new ByteBufNIO(ByteBuffer.wrap(new byte[1024])) }
        stream.read(16, 0) >> helper.defaultMessageHeader(commandMessage.getId())
        stream.read(90, 0) >> helper.defaultReply()

        when:
        connection.sendAndReceive(commandMessage, new BsonDocumentCodec(), NoOpSessionContext.INSTANCE, IgnorableRequestContext.INSTANCE,
                new OperationContext())

        then:
        commandListener.eventsWereDelivered([
                new CommandStartedEvent(null, 1, 1, connection.getDescription(), 'admin', securitySensitiveCommandName,
                        new BsonDocument()),
                new CommandSucceededEvent(null, 1, 1, connection.getDescription(), securitySensitiveCommandName,
                        new BsonDocument(), 1)])

        where:
        securitySensitiveCommand << [
                new BsonDocument('authenticate', new BsonInt32(1)),
                new BsonDocument('saslStart', new BsonInt32(1)),
                new BsonDocument('saslContinue', new BsonInt32(1)),
                new BsonDocument('getnonce', new BsonInt32(1)),
                new BsonDocument('createUser', new BsonInt32(1)),
                new BsonDocument('updateUser', new BsonInt32(1)),
                new BsonDocument('copydbgetnonce', new BsonInt32(1)),
                new BsonDocument('copydbsaslstart', new BsonInt32(1)),
                new BsonDocument('copydb', new BsonInt32(1)),
                new BsonDocument('hello', new BsonInt32(1)).append('speculativeAuthenticate', new BsonDocument()),
                new BsonDocument(LEGACY_HELLO_LOWER, new BsonInt32(1)).append('speculativeAuthenticate', new BsonDocument()),
                new BsonDocument(LEGACY_HELLO, new BsonInt32(1)).append('speculativeAuthenticate', new BsonDocument())
        ]
    }

    def 'should send failed event with elided exception in failed security-sensitive commands'() {
        given:
        def connection = getOpenedConnection()
        def commandMessage = new CommandMessage(cmdNamespace, securitySensitiveCommand, fieldNameValidator, primary(), messageSettings,
                MULTIPLE, null)
        stream.getBuffer(1024) >> { new ByteBufNIO(ByteBuffer.wrap(new byte[1024])) }
        stream.read(16, 0) >> helper.defaultMessageHeader(commandMessage.getId())
        stream.read(_, 0) >> helper.reply('{ok : 0, errmsg : "failed"}')

        when:
        connection.sendAndReceive(commandMessage, new BsonDocumentCodec(), NoOpSessionContext.INSTANCE, IgnorableRequestContext.INSTANCE,
                new OperationContext())

        then:
        thrown(MongoCommandException)
        CommandFailedEvent failedEvent = commandListener.getEvents().get(1)
        failedEvent.throwable.class == MongoCommandException
        MongoCommandException e = failedEvent.throwable
        e.response == new BsonDocument()

        where:
        securitySensitiveCommand << [
                new BsonDocument('authenticate', new BsonInt32(1)),
                new BsonDocument('saslStart', new BsonInt32(1)),
                new BsonDocument('saslContinue', new BsonInt32(1)),
                new BsonDocument('getnonce', new BsonInt32(1)),
                new BsonDocument('createUser', new BsonInt32(1)),
                new BsonDocument('updateUser', new BsonInt32(1)),
                new BsonDocument('copydbgetnonce', new BsonInt32(1)),
                new BsonDocument('copydbsaslstart', new BsonInt32(1)),
                new BsonDocument('copydb', new BsonInt32(1)),
                new BsonDocument('hello', new BsonInt32(1)).append('speculativeAuthenticate', new BsonDocument()),
                new BsonDocument(LEGACY_HELLO_LOWER, new BsonInt32(1)).append('speculativeAuthenticate', new BsonDocument()),
                new BsonDocument(LEGACY_HELLO, new BsonInt32(1)).append('speculativeAuthenticate', new BsonDocument())
        ]
    }

    def 'should send events for successful asynchronous command'() {
        given:
        def connection = getOpenedConnection()
        def pingCommandDocument = new BsonDocument('ping', new BsonInt32(1))
        def commandMessage = new CommandMessage(cmdNamespace, pingCommandDocument, fieldNameValidator, primary(), messageSettings, MULTIPLE,
                null)
        def callback = new FutureResultCallback()

        stream.getBuffer(1024) >> { new ByteBufNIO(ByteBuffer.wrap(new byte[1024])) }
        stream.writeAsync(_, _) >> { buffers, handler ->
            handler.completed(null)
        }
        stream.readAsync(16, _) >> { numBytes, handler ->
            handler.completed(helper.defaultMessageHeader(commandMessage.getId()))
        }
        stream.readAsync(90, _) >> { numBytes, handler ->
            handler.completed(helper.defaultReply())
        }

        when:
        connection.sendAndReceiveAsync(commandMessage, new BsonDocumentCodec(), NoOpSessionContext.INSTANCE,
                IgnorableRequestContext.INSTANCE, new OperationContext(), callback)
        callback.get()

        then:
        commandListener.eventsWereDelivered([
                new CommandStartedEvent(null, 1, 1, connection.getDescription(), 'admin', 'ping',
                        pingCommandDocument.append('$db', new BsonString('admin'))),
                new CommandSucceededEvent(null, 1, 1, connection.getDescription(), 'ping',
                        new BsonDocument('ok', new BsonInt32(1)), 1000)])
    }

    def 'should send events for successful asynchronous command with decoding error'() {
        given:
        def connection = getOpenedConnection()
        def pingCommandDocument = new BsonDocument('ping', new BsonInt32(1))
        def commandMessage = new CommandMessage(cmdNamespace, pingCommandDocument, fieldNameValidator, primary(), messageSettings, MULTIPLE,
                null)
        def callback = new FutureResultCallback()

        stream.getBuffer(1024) >> { new ByteBufNIO(ByteBuffer.wrap(new byte[1024])) }
        stream.writeAsync(_, _) >> { buffers, handler ->
            handler.completed(null)
        }
        stream.readAsync(16, _) >> { numBytes, handler ->
            handler.completed(helper.defaultMessageHeader(commandMessage.getId()))
        }
        stream.readAsync(90, _) >> { numBytes, handler ->
            handler.completed(helper.defaultReply())
        }

        when:
        connection.sendAndReceiveAsync(commandMessage, {
            BsonReader reader, DecoderContext decoderContext -> throw new CodecConfigurationException('')
        }, NoOpSessionContext.INSTANCE, IgnorableRequestContext.INSTANCE, new OperationContext(), callback)
        callback.get()

        then:
        thrown(CodecConfigurationException)
        commandListener.eventsWereDelivered([
                new CommandStartedEvent(null, 1, 1, connection.getDescription(), 'admin', 'ping',
                        pingCommandDocument.append('$db', new BsonString('admin'))),
                new CommandSucceededEvent(null, 1, 1, connection.getDescription(), 'ping',
                        new BsonDocument('ok', new BsonInt32(1)), 1000)])
    }


    def 'should send events for asynchronous command failure with exception writing message'() {
        given:
        def connection = getOpenedConnection()
        def pingCommandDocument = new BsonDocument('ping', new BsonInt32(1))
        def commandMessage = new CommandMessage(cmdNamespace, pingCommandDocument, fieldNameValidator, primary(), messageSettings, MULTIPLE,
                null)
        def callback = new FutureResultCallback()

        stream.getBuffer(1024) >> { new ByteBufNIO(ByteBuffer.wrap(new byte[1024])) }
        stream.writeAsync(_, _) >> { buffers, handler ->
            handler.failed(new MongoSocketWriteException('failed', serverAddress, new IOException()))
        }

        when:
        connection.sendAndReceiveAsync(commandMessage, new BsonDocumentCodec(), NoOpSessionContext.INSTANCE,
                IgnorableRequestContext.INSTANCE, new OperationContext(), callback)
        callback.get()

        then:
        def e = thrown(MongoSocketWriteException)
        commandListener.eventsWereDelivered([
                new CommandStartedEvent(null, 1, 1, connection.getDescription(), 'admin', 'ping',
                        pingCommandDocument.append('$db', new BsonString('admin'))),
                new CommandFailedEvent(null, 1, 1, connection.getDescription(), 'ping', 0, e)])
    }

    def 'should send events for asynchronous command failure with exception reading header'() {
        given:
        def connection = getOpenedConnection()
        def pingCommandDocument = new BsonDocument('ping', new BsonInt32(1))
        def commandMessage = new CommandMessage(cmdNamespace, pingCommandDocument, fieldNameValidator, primary(), messageSettings, MULTIPLE,
                null)
        def callback = new FutureResultCallback()

        stream.getBuffer(1024) >> { new ByteBufNIO(ByteBuffer.wrap(new byte[1024])) }
        stream.writeAsync(_, _) >> { buffers, handler ->
            handler.completed(null)
        }
        stream.readAsync(16, _) >> { numBytes, handler ->
            handler.failed(new MongoSocketReadException('Failed to read', serverAddress))
        }

        when:
        connection.sendAndReceiveAsync(commandMessage, new BsonDocumentCodec(), NoOpSessionContext.INSTANCE,
                IgnorableRequestContext.INSTANCE, new OperationContext(), callback)
        callback.get()

        then:
        def e = thrown(MongoSocketReadException)
        commandListener.eventsWereDelivered([
                new CommandStartedEvent(null, 1, 1, connection.getDescription(), 'admin', 'ping',
                        pingCommandDocument.append('$db', new BsonString('admin'))),
                new CommandFailedEvent(null, 1, 1, connection.getDescription(), 'ping', 0, e)])
    }

    def 'should send events for asynchronous command failure with exception reading body'() {
        given:
        def connection = getOpenedConnection()
        def pingCommandDocument = new BsonDocument('ping', new BsonInt32(1))
        def commandMessage = new CommandMessage(cmdNamespace, pingCommandDocument, fieldNameValidator, primary(), messageSettings, MULTIPLE,
                null)
        def callback = new FutureResultCallback()

        stream.getBuffer(1024) >> { new ByteBufNIO(ByteBuffer.wrap(new byte[1024])) }
        stream.writeAsync(_, _) >> { buffers, handler ->
            handler.completed(null)
        }
        stream.readAsync(16, _) >> { numBytes, handler ->
            handler.completed(helper.defaultMessageHeader(commandMessage.getId()))
        }
        stream.readAsync(90, _) >> { numBytes, handler ->
            handler.failed(new MongoSocketReadException('Failed to read', serverAddress))
        }

        when:
        connection.sendAndReceiveAsync(commandMessage, new BsonDocumentCodec(), NoOpSessionContext.INSTANCE,
                IgnorableRequestContext.INSTANCE, new OperationContext(), callback)
        callback.get()

        then:
        def e = thrown(MongoSocketReadException)
        commandListener.eventsWereDelivered([
                new CommandStartedEvent(null, 1, 1, connection.getDescription(), 'admin', 'ping',
                        pingCommandDocument.append('$db', new BsonString('admin'))),
                new CommandFailedEvent(null, 1, 1, connection.getDescription(), 'ping', 0, e)])
    }

    def 'should send events for asynchronous command failure with exception from failed command'() {
        given:
        def connection = getOpenedConnection()
        def pingCommandDocument = new BsonDocument('ping', new BsonInt32(1))
        def commandMessage = new CommandMessage(cmdNamespace, pingCommandDocument, fieldNameValidator, primary(), messageSettings, MULTIPLE,
                null)
        def callback = new FutureResultCallback()
        def response = '{ok : 0, errmsg : "failed"}'

        stream.getBuffer(1024) >> { new ByteBufNIO(ByteBuffer.wrap(new byte[1024])) }
        stream.writeAsync(_, _) >> { buffers, handler ->
            handler.completed(null)
        }
        stream.readAsync(16, _) >> { numBytes, handler ->
            handler.completed(helper.defaultMessageHeader(commandMessage.getId()))
        }
        stream.readAsync(_, _) >> { numBytes, handler ->
            handler.completed(helper.reply(response))
        }

        when:
        connection.sendAndReceiveAsync(commandMessage, new BsonDocumentCodec(), NoOpSessionContext.INSTANCE,
                IgnorableRequestContext.INSTANCE, new OperationContext(), callback)
        callback.get()

        then:
        def e = thrown(MongoCommandException)
        commandListener.eventsWereDelivered([
                new CommandStartedEvent(null, 1, 1, connection.getDescription(), 'admin', 'ping',
                        pingCommandDocument.append('$db', new BsonString('admin'))),
                new CommandFailedEvent(null, 1, 1, connection.getDescription(), 'ping', 0, e)])
    }

    def 'should send events with elided command and response in successful security-sensitive asynchronous commands'() {
        given:
        def securitySensitiveCommandName = securitySensitiveCommand.keySet().iterator().next()
        def connection = getOpenedConnection()
        def commandMessage = new CommandMessage(cmdNamespace, securitySensitiveCommand, fieldNameValidator, primary(), messageSettings,
                MULTIPLE, null)
        def callback = new FutureResultCallback()

        stream.getBuffer(1024) >> { new ByteBufNIO(ByteBuffer.wrap(new byte[1024])) }
        stream.writeAsync(_, _) >> { buffers, handler ->
            handler.completed(null)
        }
        stream.readAsync(16, _) >> { numBytes, handler ->
            handler.completed(helper.defaultMessageHeader(commandMessage.getId()))
        }
        stream.readAsync(90, _) >> { numBytes, handler ->
            handler.completed(helper.defaultReply())
        }

        when:
        connection.sendAndReceiveAsync(commandMessage, new BsonDocumentCodec(), NoOpSessionContext.INSTANCE,
                IgnorableRequestContext.INSTANCE, new OperationContext(), callback)
        callback.get()

        then:
        commandListener.eventsWereDelivered([
                new CommandStartedEvent(null, 1, 1, connection.getDescription(), 'admin', securitySensitiveCommandName,
                        new BsonDocument()),
                new CommandSucceededEvent(null, 1, 1, connection.getDescription(), securitySensitiveCommandName,
                        new BsonDocument(), 1)])

        where:
        securitySensitiveCommand << [
                new BsonDocument('authenticate', new BsonInt32(1)),
                new BsonDocument('saslStart', new BsonInt32(1)),
                new BsonDocument('saslContinue', new BsonInt32(1)),
                new BsonDocument('getnonce', new BsonInt32(1)),
                new BsonDocument('createUser', new BsonInt32(1)),
                new BsonDocument('updateUser', new BsonInt32(1)),
                new BsonDocument('copydbgetnonce', new BsonInt32(1)),
                new BsonDocument('copydbsaslstart', new BsonInt32(1)),
                new BsonDocument('copydb', new BsonInt32(1)),
                new BsonDocument('hello', new BsonInt32(1)).append('speculativeAuthenticate', new BsonDocument()),
                new BsonDocument(LEGACY_HELLO_LOWER, new BsonInt32(1)).append('speculativeAuthenticate', new BsonDocument()),
                new BsonDocument(LEGACY_HELLO, new BsonInt32(1)).append('speculativeAuthenticate', new BsonDocument())
        ]
    }

    private static boolean expectException(rcvdCallbck) {
        try {
            rcvdCallbck.get()
            false
        } catch (MongoSocketWriteException) {
            true
        }
    }
}
