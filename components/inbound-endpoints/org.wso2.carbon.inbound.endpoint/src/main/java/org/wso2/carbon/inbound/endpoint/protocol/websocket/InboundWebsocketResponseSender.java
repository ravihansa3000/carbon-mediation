/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.inbound.endpoint.protocol.websocket;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.apache.axiom.om.OMOutputFormat;
import org.apache.axis2.transport.MessageFormatter;
import org.apache.axis2.transport.base.BaseUtils;
import org.apache.axis2.util.MessageProcessorSelector;
import org.apache.commons.io.output.WriterOutputStream;
import org.apache.log4j.Logger;
import org.apache.synapse.MessageContext;
import org.apache.synapse.core.axis2.Axis2MessageContext;
import org.apache.synapse.inbound.InboundResponseSender;
import org.apache.synapse.transport.passthru.util.RelayUtils;
import org.wso2.carbon.inbound.endpoint.protocol.websocket.management.WebsocketEndpointManager;
import org.wso2.carbon.inbound.endpoint.protocol.websocket.management.WebsocketSubscriberPathManager;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;

public class InboundWebsocketResponseSender implements InboundResponseSender {

    private Logger log = Logger.getLogger(InboundWebsocketResponseSender.class);
    private InboundWebsocketSourceHandler sourceHandler;

    public InboundWebsocketResponseSender(InboundWebsocketSourceHandler sourceHandler) {
        this.sourceHandler = sourceHandler;
    }

    public InboundWebsocketSourceHandler getSourceHandler() {
        return sourceHandler;
    }

    @Override
    public void sendBack(MessageContext msgContext) {
        if (msgContext != null) {

            if (msgContext.getProperty(InboundWebsocketConstants.WEBSOCKET_SOURCE_HANDSHAKE_PRESENT) != null &&
                    msgContext.getProperty(InboundWebsocketConstants.WEBSOCKET_SOURCE_HANDSHAKE_PRESENT).equals(true)) {
                return;
            } else if (msgContext.getProperty(InboundWebsocketConstants.WEBSOCKET_TARGET_HANDSHAKE_PRESENT) != null &&
                    msgContext.getProperty(InboundWebsocketConstants.WEBSOCKET_TARGET_HANDSHAKE_PRESENT).equals(true)) {
                if (msgContext.getProperty(InboundWebsocketConstants.WEBSOCKET_TARGET_HANDLER_CONTEXT) != null) {
                    ChannelHandlerContext targetCtx = (ChannelHandlerContext) msgContext
                            .getProperty(InboundWebsocketConstants.WEBSOCKET_TARGET_HANDLER_CONTEXT);
                    sourceHandler.getChannelHandlerContext().addCloseListener(targetCtx);
                }
                return;
            } else if (msgContext.getProperty(InboundWebsocketConstants.WEBSOCKET_BINARY_FRAME_PRESENT) != null &&
                    msgContext.getProperty(InboundWebsocketConstants.WEBSOCKET_BINARY_FRAME_PRESENT).equals(true)) {
                BinaryWebSocketFrame frame = (BinaryWebSocketFrame)
                        msgContext.getProperty(InboundWebsocketConstants.WEBSOCKET_BINARY_FRAME);
                InboundWebsocketChannelContext ctx = sourceHandler.getChannelHandlerContext();
                int clientBroadcastLevel = sourceHandler.getClientBroadcastLevel();
                String subscriberPath = sourceHandler.getSubscriberPath();
                WebsocketSubscriberPathManager pathManager = WebsocketSubscriberPathManager.getInstance();
                handleSendBack(frame, ctx, clientBroadcastLevel, subscriberPath, pathManager);
            } else if (msgContext.getProperty(InboundWebsocketConstants.WEBSOCKET_TEXT_FRAME_PRESENT) != null &&
                    msgContext.getProperty(InboundWebsocketConstants.WEBSOCKET_TEXT_FRAME_PRESENT).equals(true)) {
                TextWebSocketFrame frame = (TextWebSocketFrame)
                        msgContext.getProperty(InboundWebsocketConstants.WEBSOCKET_TEXT_FRAME);
                InboundWebsocketChannelContext ctx = sourceHandler.getChannelHandlerContext();
                int clientBroadcastLevel = sourceHandler.getClientBroadcastLevel();
                String subscriberPath = sourceHandler.getSubscriberPath();
                WebsocketSubscriberPathManager pathManager = WebsocketSubscriberPathManager.getInstance();
                handleSendBack(frame, ctx, clientBroadcastLevel, subscriberPath, pathManager);
            } else {
                try {
                    RelayUtils.buildMessage(((Axis2MessageContext) msgContext).getAxis2MessageContext(), false);
                    TextWebSocketFrame frame = new TextWebSocketFrame(messageContextToText(((Axis2MessageContext) msgContext)
                            .getAxis2MessageContext()));
                    InboundWebsocketChannelContext ctx = sourceHandler.getChannelHandlerContext();
                    int clientBroadcastLevel = sourceHandler.getClientBroadcastLevel();
                    String subscriberPath = sourceHandler.getSubscriberPath();
                    WebsocketSubscriberPathManager pathManager = WebsocketSubscriberPathManager.getInstance();
                    handleSendBack(frame, ctx, clientBroadcastLevel, subscriberPath, pathManager);
                } catch (XMLStreamException e) {
                    log.error("Error while building message", e);
                } catch (IOException ex) {
                    log.error("Failed for format message to specified output format", ex);
                }
            }
        }
    }

    protected void handleSendBack(WebSocketFrame frame,
                                  InboundWebsocketChannelContext ctx,
                                  int clientBroadcastLevel,
                                  String subscriberPath,
                                  WebsocketSubscriberPathManager pathManager) {
        if (clientBroadcastLevel == 0) {
            ctx.writeToChannel(frame);
        } else if (clientBroadcastLevel == 1) {
            String endpointName =
                    WebsocketEndpointManager.getInstance().getEndpointName(sourceHandler.getPort(),
                            sourceHandler.getTenantDomain());
            pathManager.broadcastOnSubscriberPath(frame, endpointName, subscriberPath);
        } else if (clientBroadcastLevel == 2) {
            String endpointName =
                    WebsocketEndpointManager.getInstance().getEndpointName(sourceHandler.getPort(),
                            sourceHandler.getTenantDomain());
            pathManager.exclusiveBroadcastOnSubscriberPath(frame, endpointName, subscriberPath, ctx);
        }
    }

    protected String messageContextToText(org.apache.axis2.context.MessageContext msgCtx)
            throws IOException {
        OMOutputFormat format = BaseUtils.getOMOutputFormat(msgCtx);
        MessageFormatter messageFormatter =
                MessageProcessorSelector.getMessageFormatter(msgCtx);
        StringWriter sw = new StringWriter();
        OutputStream out = new WriterOutputStream(sw, format.getCharSetEncoding());
        messageFormatter.writeTo(msgCtx, format, out, true);
        out.close();
        return sw.toString();
    }

}
