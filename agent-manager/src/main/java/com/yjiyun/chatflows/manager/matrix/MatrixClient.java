package com.yjiyun.chatflows.manager.matrix;
import java.io.IOException;
public interface MatrixClient {
 default void join(String roomId)throws IOException,InterruptedException{}
 void send(String roomId,String message)throws IOException,InterruptedException;
 default void sendMention(String roomId,String userId,String message)throws IOException,InterruptedException{send(roomId,message);}
 String receive(String roomId)throws IOException,InterruptedException;
 default String sync(String since,long timeoutMillis)throws IOException,InterruptedException{return "{}";}
}
