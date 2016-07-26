/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.alibaba.rocketmq.tools.command.message;

import com.alibaba.rocketmq.client.producer.DefaultMQProducer;
import com.alibaba.rocketmq.client.producer.MessageQueueSelector;
import com.alibaba.rocketmq.common.message.Message;
import com.alibaba.rocketmq.common.message.MessageQueue;
import com.alibaba.rocketmq.remoting.RPCHook;
import com.alibaba.rocketmq.tools.command.SubCommand;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;

import java.util.List;

public class CheckMsgSendRTCommand implements SubCommand {
    private static String brokerName = "";
    private static int queueId = 0;

    @Override
    public String commandName() {
        return "checkMsgSendRT";
    }

    @Override
    public String commandDesc() {
        return "check message send response time";
    }

    @Override
    public Options buildCommandlineOptions(Options options) {
        Option opt = new Option("t", "topic", true, "topic name");
        opt.setRequired(true);
        options.addOption(opt);

        opt = new Option("a", "amout", true, "message amout | default 100");
        opt.setRequired(false);
        options.addOption(opt);

        opt = new Option("s", "size", true, "message size | default 128 Byte");
        opt.setRequired(true);
        options.addOption(opt);
        return options;
    }

    @Override
    public void execute(CommandLine commandLine, Options options, RPCHook rpcHook) {
        DefaultMQProducer producer = new DefaultMQProducer(rpcHook);
        producer.setProducerGroup(Long.toString(System.currentTimeMillis()));

        try {
            producer.start();
            long start = 0;
            long end = 0;
            long timeElapsed = 0;
            boolean sendSuccess = false;
            String topic = commandLine.getOptionValue('t').trim();
            long amount = !commandLine.hasOption('a') ? 100 : Long.parseLong(commandLine
                    .getOptionValue('a').trim());
            long msgSize = !commandLine.hasOption('s') ? 128 : Long.parseLong(commandLine
                    .getOptionValue('s').trim());
            Message msg = new Message(topic, getStringBySize(msgSize).getBytes());

            System.out.printf("%-32s  %-4s  %-20s    %s%n",//
                    "#Broker Name",//
                    "#QID",//
                    "#Send Result",//
                    "#RT"//
            );
            for (int i = 0; i < amount; i++) {
                start = System.currentTimeMillis();
                try {
                    producer.send(msg, new MessageQueueSelector() {
                        @Override
                        public MessageQueue select(List<MessageQueue> mqs, Message msg, Object arg) {
                            int queueIndex = (Integer) arg % mqs.size();
                            MessageQueue queue = mqs.get(queueIndex);
                            brokerName = queue.getBrokerName();
                            queueId = queue.getQueueId();
                            return queue;
                        }
                    }, i);
                    sendSuccess = true;
                    end = System.currentTimeMillis();
                } catch (Exception e) {
                    sendSuccess = false;
                    end = System.currentTimeMillis();
                }


                if (i != 0) {
                    timeElapsed += (end - start);
                }

                System.out.printf("%-32s  %-4s  %-20s    %s%n",//
                        brokerName,//
                        queueId,//
                        sendSuccess,//
                        (end - start)//
                );
            }

            double rt = (double) timeElapsed / (amount - 1);
            System.out.printf("Avg RT: %s%n", String.format("%.2f", rt));
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            producer.shutdown();
        }
    }

    public String getStringBySize(long size) {
        StringBuilder res = new StringBuilder();
        for (int i = 0; i < size; i++) {
            res.append('a');
        }
        return res.toString();
    }
}
