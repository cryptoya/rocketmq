/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.controller.impl.controller;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.common.MixAll;
import org.apache.rocketmq.common.ControllerConfig;
import org.apache.rocketmq.common.protocol.RequestCode;
import org.apache.rocketmq.common.protocol.header.namesrv.BrokerHeartbeatRequestHeader;
import org.apache.rocketmq.common.protocol.header.namesrv.controller.BrokerRegisterRequestHeader;
import org.apache.rocketmq.common.protocol.header.namesrv.controller.BrokerRegisterResponseHeader;
import org.apache.rocketmq.common.protocol.header.namesrv.controller.GetReplicaInfoRequestHeader;
import org.apache.rocketmq.common.protocol.header.namesrv.controller.GetReplicaInfoResponseHeader;
import org.apache.rocketmq.controller.ControllerManager;
import org.apache.rocketmq.controller.impl.DLedgerController;
import org.apache.rocketmq.remoting.RemotingClient;
import org.apache.rocketmq.remoting.netty.NettyClientConfig;
import org.apache.rocketmq.remoting.netty.NettyRemotingClient;
import org.apache.rocketmq.remoting.netty.NettyServerConfig;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.apache.rocketmq.common.protocol.ResponseCode.CONTROLLER_NOT_LEADER;
import static org.apache.rocketmq.remoting.protocol.RemotingSysResponseCode.SUCCESS;
import static org.junit.Assert.assertEquals;

public class ControllerManagerTest {
    private List<String> baseDirs;
    private List<ControllerManager> controllers;
    private NettyRemotingClient remotingClient;
    private NettyRemotingClient remotingClient1;

    public ControllerManager launchManager(final String group, final String peers, final String selfId) {
        final String path = "/tmp" + File.separator + group + File.separator + selfId;
        baseDirs.add(path);

        final ControllerConfig config = new ControllerConfig();
        config.setControllerDLegerGroup(group);
        config.setControllerDLegerPeers(peers);
        config.setControllerDLegerSelfId(selfId);
        config.setControllerStorePath(path);
        config.setMappedFileSize(10 * 1024 * 1024);
        config.setEnableElectUncleanMaster(true);
        config.setScanNotActiveBrokerInterval(2000L);

        final NettyServerConfig serverConfig = new NettyServerConfig();

        final ControllerManager manager = new ControllerManager(config, serverConfig, new NettyClientConfig());
        manager.initialize();
        manager.start();
        this.controllers.add(manager);
        return manager;
    }

    @Before
    public void startup() {
        this.baseDirs = new ArrayList<>();
        this.controllers = new ArrayList<>();
        this.remotingClient = new NettyRemotingClient(new NettyClientConfig());
        this.remotingClient.start();
        this.remotingClient1 = new NettyRemotingClient(new NettyClientConfig());
        this.remotingClient1.start();
    }

    public ControllerManager waitLeader(final List<ControllerManager> controllers) throws Exception {
        if (controllers.isEmpty()) {
            return null;
        }
        DLedgerController c1 = (DLedgerController) controllers.get(0).getController();
        while (c1.getMemberState().getLeaderId() == null) {
            Thread.sleep(1000);
        }
        String leaderId = c1.getMemberState().getLeaderId();
        System.out.println("New leader " + leaderId);
        for (ControllerManager controllerManager : controllers) {
            final DLedgerController controller = (DLedgerController) controllerManager.getController();
            if (controller.getMemberState().getSelfId().equals(leaderId)) {
                return controllerManager;
            }
        }
        return null;
    }

    public void mockData() {
        String group = UUID.randomUUID().toString();
        String peers = String.format("n0-localhost:%d;n1-localhost:%d;n2-localhost:%d", 30000, 30001, 30002);
        launchManager(group, peers, "n0");
        launchManager(group, peers, "n1");
        launchManager(group, peers, "n2");
    }

    /**
     * Register broker to controller
     */
    public BrokerRegisterResponseHeader registerBroker(
        final String controllerAddress, final String clusterName,
        final String brokerName, final String address, final RemotingClient client) throws Exception {

        final BrokerRegisterRequestHeader requestHeader = new BrokerRegisterRequestHeader(clusterName, brokerName, address);
        // Timeout = 3000
        requestHeader.setHeartbeatTimeoutMillis(4000L);
        final RemotingCommand request = RemotingCommand.createRequestCommand(RequestCode.CONTROLLER_REGISTER_BROKER, requestHeader);
        final RemotingCommand response = client.invokeSync(controllerAddress, request, 3000);
        assert response != null;
        switch (response.getCode()) {
            case SUCCESS: {
                return response.decodeCommandCustomHeader(BrokerRegisterResponseHeader.class);
            }
            case CONTROLLER_NOT_LEADER: {
                throw new MQBrokerException(response.getCode(), "Controller leader was changed");
            }
        }
        throw new MQBrokerException(response.getCode(), response.getRemark());
    }

    @Test
    public void testSomeApi() throws Exception {
        mockData();
        final ControllerManager leader = waitLeader(this.controllers);
        String leaderAddr = "localhost" + ":" + leader.getController().getRemotingServer().localListenPort();

        // Register two broker, the first one is master.
        final BrokerRegisterResponseHeader responseHeader1 = registerBroker(leaderAddr, "cluster1", "broker1", "127.0.0.1:8000", this.remotingClient);
        assert responseHeader1 != null;
        assertEquals(responseHeader1.getBrokerId(), MixAll.MASTER_ID);

        final BrokerRegisterResponseHeader responseHeader2 = registerBroker(leaderAddr, "cluster1", "broker1", "127.0.0.1:8001", this.remotingClient1);
        assert responseHeader2 != null;
        assertEquals(responseHeader2.getBrokerId(), 2);

        // Send heartbeat for broker2
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleAtFixedRate(()-> {
            final BrokerHeartbeatRequestHeader heartbeatRequestHeader = new BrokerHeartbeatRequestHeader();
            heartbeatRequestHeader.setClusterName("cluster1");
            heartbeatRequestHeader.setBrokerName("broker1");
            heartbeatRequestHeader.setBrokerAddr("127.0.0.1:8001");
            final RemotingCommand request = RemotingCommand.createRequestCommand(RequestCode.BROKER_HEARTBEAT, heartbeatRequestHeader);
            System.out.println("send heartbeat success");
            try {
                final RemotingCommand remotingCommand = this.remotingClient1.invokeSync(leaderAddr, request, 3000);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, 2000L, TimeUnit.MILLISECONDS);


        // Wait until the master is expired.
        Thread.sleep(6000);

        // The new master should be broker2.
        final GetReplicaInfoRequestHeader requestHeader = new GetReplicaInfoRequestHeader("broker1");
        final RemotingCommand request = RemotingCommand.createRequestCommand(RequestCode.CONTROLLER_GET_REPLICA_INFO, requestHeader);
        final RemotingCommand response = this.remotingClient1.invokeSync(leaderAddr, request, 3000);
        final GetReplicaInfoResponseHeader responseHeader = response.decodeCommandCustomHeader(GetReplicaInfoResponseHeader.class);
        assertEquals(responseHeader.getMasterAddress(), "127.0.0.1:8001");

        executor.shutdown();
    }

    @After
    public void tearDown() {
        for (ControllerManager controller : this.controllers) {
            controller.shutdown();
        }
        for (String dir : this.baseDirs) {
            System.out.println("Delete file " + dir);
            new File(dir).delete();
        }
        this.remotingClient.shutdown();
        this.remotingClient1.shutdown();
    }
}
