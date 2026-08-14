package com.yjiyun.chatflows.manager;
import com.sun.net.httpserver.HttpServer; import com.yjiyun.chatflows.manager.api.*; import com.yjiyun.chatflows.manager.security.AuthService; import java.io.IOException; import java.net.InetSocketAddress; import java.util.concurrent.*;
public final class ManagerServer implements AutoCloseable {
 private final HttpServer server; private final ExecutorService executor=Executors.newCachedThreadPool();
 public ManagerServer(ManagerComposition c)throws IOException{ManagerConfig cfg=c.config();AuthService auth=new AuthService(cfg.managerAuthToken(),cfg.managerAdminToken());server=HttpServer.create(new InetSocketAddress(cfg.managerHost(),cfg.managerPort()),0);server.createContext("/api/v1/orchestrations",new OrchestrationController(auth,c.pipeline(),c.runs(),c.planner(),c.tasks(),c.matrix(),cfg.leaderIds(),cfg.humanIds(),cfg.leaderRoomId()));server.createContext("/api/v1/health",new HealthController(c,auth));server.setExecutor(executor);}
 public void start(){server.start();} public int port(){return server.getAddress().getPort();} public void close(){server.stop(1);executor.shutdownNow();}
}
