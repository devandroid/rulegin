package com.github.rulegin.actors.rpc;

import akka.event.Logging;
import akka.event.LoggingAdapter;
import com.github.rulegin.actors.msg.core.RpcSessionCreateRequestMsg;
import com.github.rulegin.actors.msg.core.RpcSessionTellMsg;
import com.github.rulegin.actors.ActorSystemContext;
import com.github.rulegin.actors.ContextAwareActor;
import com.github.rulegin.actors.service.ContextBasedCreator;
import com.github.rulegin.actors.service.cluster.rpc.GrpcSession;
import com.github.rulegin.actors.service.cluster.rpc.GrpcSessionListener;
import com.github.rulegin.actors.service.cluster.ServerAddress;
import com.github.rulegin.server.gen.cluster.ClusterAPIProtos;
import com.github.rulegin.server.gen.cluster.ClusterRpcServiceGrpc;
import io.grpc.Channel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.util.UUID;

/**
 */
public class RpcSessionActor extends ContextAwareActor {

    private final LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    private final UUID sessionId;
    private GrpcSession session;
    private GrpcSessionListener listener;

    public RpcSessionActor(ActorSystemContext systemContext, UUID sessionId) {
        super(systemContext);
        this.sessionId = sessionId;
    }

    @Override
    public void onReceive(Object msg) throws Exception {
        log.info("RpcSessionActor received msg : {}",msg);

        if (msg instanceof RpcSessionTellMsg) {
            tell((RpcSessionTellMsg) msg);
        } else if (msg instanceof RpcSessionCreateRequestMsg) {
            initSession((RpcSessionCreateRequestMsg) msg);
        }
    }

    private void tell(RpcSessionTellMsg msg) {
        //
        session.sendMsg(msg.getMsg());
    }

    @Override
    public void postStop() {
        log.info("Closing session -> {}", session.getRemoteServer());
        session.close();
    }

    private void initSession(RpcSessionCreateRequestMsg msg) {
        log.info("[{}] Initializing session", context().self());
        ServerAddress remoteServer = msg.getRemoteAddress();
        listener = new BasicRpcSessionListener(systemContext, context().parent(), context().self());
        if (msg.getRemoteAddress() == null) {
            // Server session
            session = new GrpcSession(listener);
            session.setOutputStream(msg.getResponseObserver());
            session.initInputStream();
            session.initOutputStream();
            systemContext.getRpcService().onSessionCreated(msg.getMsgUid(), session.getInputStream());
        } else {
            // Client session
            Channel channel = ManagedChannelBuilder.forAddress(remoteServer.getHost(), remoteServer.getPort()).usePlaintext(true).build();
            session = new GrpcSession(remoteServer, listener);
            session.initInputStream();

            ClusterRpcServiceGrpc.ClusterRpcServiceStub stub = ClusterRpcServiceGrpc.newStub(channel);
            StreamObserver<ClusterAPIProtos.ToRpcServerMessage> outputStream = stub.handlePluginMsgs(session.getInputStream());

            session.setOutputStream(outputStream);
            session.initOutputStream();
            outputStream.onNext(toConnectMsg());
        }
    }

    public static class ActorCreator extends ContextBasedCreator<RpcSessionActor> {
        private static final long serialVersionUID = 1L;

        private final UUID sessionId;

        public ActorCreator(ActorSystemContext context, UUID sessionId) {
            super(context);
            this.sessionId = sessionId;
        }

        @Override
        public RpcSessionActor create() throws Exception {
            return new RpcSessionActor(context, sessionId);
        }
    }

    private ClusterAPIProtos.ToRpcServerMessage toConnectMsg() {
        ServerAddress instance = systemContext.getDiscoveryService().getCurrentServer().getServerAddress();
        return ClusterAPIProtos.ToRpcServerMessage.newBuilder().setConnectMsg(
                ClusterAPIProtos.ConnectRpcMessage.newBuilder().setServerAddress(
                        ClusterAPIProtos.ServerAddress.newBuilder().setHost(instance.getHost()).setPort(instance.getPort()).build()).build()).build();

    }
}
