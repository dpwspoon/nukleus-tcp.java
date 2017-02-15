/**
 * Copyright 2016-2017 The Reaktivity Project
 *
 * The Reaktivity Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.reaktivity.nukleus.tcp.internal.streams;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.rules.RuleChain.outerRule;
import static org.reaktivity.nukleus.tcp.internal.writer.stream.StreamFactory.WRITE_SPIN_COUNT;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.byteman.contrib.bmunit.BMScript;
import org.jboss.byteman.contrib.bmunit.BMUnitConfig;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.kaazing.k3po.junit.annotation.Specification;
import org.kaazing.k3po.junit.rules.K3poRule;
import org.reaktivity.reaktor.test.NukleusRule;

@RunWith(org.jboss.byteman.contrib.bmunit.BMUnitRunner.class)
public class ClientPartialWriteIT
{
    private final K3poRule k3po = new K3poRule()
        .addScriptRoot("route", "org/reaktivity/specification/nukleus/tcp/control/route")
        .addScriptRoot("streams", "org/reaktivity/specification/nukleus/tcp/streams");

    private final TestRule timeout = new DisableOnDebug(new Timeout(5, SECONDS));

    private final NukleusRule nukleus = new NukleusRule("tcp")
        .directory("target/nukleus-itests")
        .commandBufferCapacity(1024)
        .responseBufferCapacity(1024)
        .counterValuesBufferCapacity(1024)
        .streams("tcp", "source#partition");

    @Rule
    public final TestRule chain = outerRule(PartialWriteHelper.RULE).around(nukleus).around(k3po).around(timeout);

    @Test
    @Specification({
        "${route}/output/new/controller",
        "${streams}/client.sent.data/client/source"
    })
    @BMUnitConfig(loadDirectory="src/test/resources", debug=false, verbose=false)
    @BMScript(value="PartialWriteIT.btm")
    public void shouldSpinWrite() throws Exception
    {
        for (int i=0; i < WRITE_SPIN_COUNT - 1; i++)
        {
            PartialWriteHelper.addWriteResult(0);
        }
        shouldReceiveClientSentData("client data");
    }

    @Test
    @Specification({
        "${route}/output/new/controller",
        "${streams}/client.sent.data/client/source"
    })
    @BMUnitConfig(loadDirectory="src/test/resources", debug=false, verbose=false)
    @BMScript(value="PartialWriteIT.btm")
    public void shouldFinishWriteWhenSocketIsWritableAgain() throws Exception
    {
        PartialWriteHelper.addWriteResult(5);
        shouldReceiveClientSentData("client data");
    }

    @Test
    @Specification({
        "${route}/output/new/controller",
        "${streams}/client.sent.data/client/source"
    })
    @BMUnitConfig(loadDirectory="src/test/resources", debug=false, verbose=false)
    @BMScript(value="PartialWriteIT.btm")
    public void shouldHandleMultiplePartialWrites() throws Exception
    {
        PartialWriteHelper.addWriteResult(2);
        PartialWriteHelper.addWriteResult(3);
        PartialWriteHelper.addWriteResult(1);
        shouldReceiveClientSentData("client data");
    }

    @Test
    @Specification({
        "${route}/output/new/controller",
        "${streams}/client.sent.data.multiple.frames/client/source"
    })
    @BMUnitConfig(loadDirectory="src/test/resources", debug=false, verbose=false)
    @BMScript(value="PartialWriteIT.btm")
    public void shouldWriteWhenMoreDataArrivesWhileAwaitingSocketWritable() throws Exception
    {
        // processData will be called from each of the two data frames. Make the first
        // do a partial write, then write nothing until handleWrite is called after the
        // second processData call, when we write everything.
        AtomicBoolean finishWrite = new AtomicBoolean(false);
        PartialWriteHelper.addWriteResult(5);
        PartialWriteHelper.setWriteResultProvider(caller ->
        {
            if (caller.equals("processData"))
            {
                finishWrite.set(true);
                return 0;
            }
            else
            {
                return finishWrite.get() ? null : 0;
            }
        });

        shouldReceiveClientSentData("client data 1client data 2");
    }

    @Test
    @Specification({
        "${route}/output/new/controller",
        "${streams}/client.sent.data.then.end/client/source"
    })
    @BMUnitConfig(loadDirectory="src/test/resources", debug=false, verbose=false)
    @BMScript(value="PartialWriteIT.btm")
    public void shouldHandleEndOfStreamWithPendingWrite() throws Exception
    {
        PartialWriteHelper.addWriteResult(5);
        AtomicBoolean endWritten = new AtomicBoolean(false);
        PartialWriteHelper.zeroWriteUnless(endWritten::get);

        try (ServerSocket server = new ServerSocket())
        {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("127.0.0.1", 0x1f90));
            server.setSoTimeout((int) SECONDS.toMillis(5));

            k3po.start();
            k3po.awaitBarrier("ROUTED_OUTPUT");

            try (Socket socket = server.accept())
            {
                k3po.notifyBarrier("ROUTED_INPUT");

                final InputStream in = socket.getInputStream();
                k3po.awaitBarrier("END_WRITTEN");
                endWritten.set(true);

                byte[] buf = new byte["client data".length() + 10];
                int offset = 0;

                int read = 0;
                boolean closed = false;
                do
                {
                    read = in.read(buf, offset, buf.length - offset);
                    if (read == -1)
                    {
                        closed = true;
                        break;
                    }
                    offset += read;
                } while (offset < "client data".length());
                assertEquals("client data", new String(buf, 0, offset, UTF_8));
                if (!closed)
                {
                    closed = (in.read() == -1);
                }
                assertTrue("Stream was not closed", closed);
                k3po.finish();
            }
        }
    }

    @Test
    @Specification({
        "${route}/output/new/controller",
        "${streams}/client.sent.data.after.end/client/source"
    })
    @BMUnitConfig(loadDirectory="src/test/resources", debug=false, verbose=false)
    @BMScript(value="PartialWriteIT.btm")
    public void shouldResetIfDataReceivedAfterEndOfStreamWithPendingWrite() throws Exception
    {
        PartialWriteHelper.addWriteResult(6);
        AtomicBoolean resetReceived = new AtomicBoolean(false);
        PartialWriteHelper.zeroWriteUnless(resetReceived::get);

        try (ServerSocket server = new ServerSocket())
        {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("127.0.0.1", 0x1f90));
            server.setSoTimeout((int) SECONDS.toMillis(5));

            k3po.start();
            k3po.awaitBarrier("ROUTED_OUTPUT");

            try (Socket socket = server.accept())
            {
                k3po.notifyBarrier("ROUTED_INPUT");

                k3po.awaitBarrier("RESET_RECEIVED");
                resetReceived.set(true);

                final InputStream in = socket.getInputStream();

                byte[] buf = new byte["client data".length() + 10];
                int offset = 0;

                int read = 0;
                boolean closed = false;
                do
                {
                    read = in.read(buf, offset, buf.length - offset);
                    if (read == -1)
                    {
                        closed = true;
                        break;
                    }
                    offset += read;
                } while (offset < "client data".length());
                assertEquals("client data", new String(buf, 0, offset, UTF_8));

                if (!closed)
                {
                    closed = (in.read() == -1);
                }
                assertTrue("Stream was not closed", closed);

                k3po.finish();
            }
        }
    }

    private void shouldReceiveClientSentData(String expectedData) throws Exception
    {
        shouldReceiveClientSentData(expectedData, false);
    }

    private void shouldReceiveClientSentData(String expectedData, boolean expectStreamClosed) throws Exception
    {
        try (ServerSocket server = new ServerSocket())
        {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("127.0.0.1", 0x1f90));
            server.setSoTimeout((int) SECONDS.toMillis(5));

            k3po.start();
            k3po.awaitBarrier("ROUTED_OUTPUT");

            try (Socket socket = server.accept())
            {
                k3po.notifyBarrier("ROUTED_INPUT");

                final InputStream in = socket.getInputStream();

                byte[] buf = new byte[expectedData.length() + 10];
                int offset = 0;

                int read = 0;
                boolean closed = false;
                do
                {
                    read = in.read(buf, offset, buf.length - offset);
                    if (read == -1)
                    {
                        closed = true;
                        break;
                    }
                    offset += read;
                } while (offset < expectedData.length());
                assertEquals(expectedData, new String(buf, 0, offset, UTF_8));

                if (expectStreamClosed)
                {
                    if (!closed)
                    {
                        closed = (in.read() == -1);
                    }
                    assertTrue("Stream was not closed", closed);
                }

                k3po.finish();
            }
        }
    }
}
