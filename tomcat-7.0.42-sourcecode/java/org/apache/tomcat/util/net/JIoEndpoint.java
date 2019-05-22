/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.tomcat.util.net;

import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.ExceptionUtils;
import org.apache.tomcat.util.net.AbstractEndpoint.Handler.SocketState;


/**
 * Handle incoming TCP connections.
 *
 * This class implement a simple server model: one listener thread accepts on a socket and
 * creates a new worker thread for each incoming connection.
 *
 * More advanced Endpoints will reuse the threads, use queues, etc.
 *
 * @author James Duncan Davidson
 * @author Jason Hunter
 * @author James Todd
 * @author Costin Manolache
 * @author Gal Shachor
 * @author Yoav Shapira
 * @author Remy Maucherat
 */
public class JIoEndpoint extends AbstractEndpoint {


    // -------------------------------------------------------------- Constants

    private static final Log log = LogFactory.getLog(JIoEndpoint.class);

    // ----------------------------------------------------------------- Fields

    /**
     * Associated server socket.
     */
    protected ServerSocket serverSocket = null;


    // ------------------------------------------------------------ Constructor

    public JIoEndpoint() {
        // Set maxConnections to zero so we can tell if the user has specified
        // their own value on the connector when we reach bind()
        setMaxConnections(0);
    }

    // ------------------------------------------------------------- Properties

    /**
     * Handling of accepted sockets.
     * 这个 handler
     */
    protected Handler handler = null;
    public void setHandler(Handler handler ) { this.handler = handler; }
    public Handler getHandler() { return handler; }

    /**
     * Server socket factory.
     */
    protected ServerSocketFactory serverSocketFactory = null;
    public void setServerSocketFactory(ServerSocketFactory factory) { this.serverSocketFactory = factory; }
    public ServerSocketFactory getServerSocketFactory() { return serverSocketFactory; }

    /**
     * Port in use.
     */
    @Override
    public int getLocalPort() {
        ServerSocket s = serverSocket;
        if (s == null) {
            return -1;
        } else {
            return s.getLocalPort();
        }
    }

    /*
     * Optional feature support.
     */
    @Override
    public boolean getUseSendfile() { return false; } // Not supported
    @Override
    public boolean getUseComet() { return false; } // Not supported
    @Override
    public boolean getUseCometTimeout() { return false; } // Not supported
    @Override
    public boolean getDeferAccept() { return false; } // Not supported
    @Override
    public boolean getUsePolling() { return false; } // Not supported


    // ------------------------------------------------ Handler Inner Interface

    /**
     * Bare bones interface used for socket processing. Per thread data is to be
     * stored in the ThreadWithAttributes extra folders, or alternately in
     * thread local fields.
     */
    public interface Handler extends AbstractEndpoint.Handler {
        public SocketState process(SocketWrapper<Socket> socket,
                SocketStatus status);
        public SSLImplementation getSslImplementation();
    }


    /**
     * Async timeout thread
     */
    protected class AsyncTimeout implements Runnable {
        /**
         * The background thread that checks async requests and fires the
         * timeout if there has been no activity.
         */
        @Override
        public void run() {

            // Loop until we receive a shutdown command
            while (running) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    // Ignore
                }
                long now = System.currentTimeMillis();
                Iterator<SocketWrapper<Socket>> sockets =
                    waitingRequests.iterator();
                while (sockets.hasNext()) {
                    SocketWrapper<Socket> socket = sockets.next();
                    long access = socket.getLastAccess();
                    if (socket.getTimeout() > 0 &&
                            (now-access)>socket.getTimeout()) {
                        // 异步解析 socket，将socket放入解析socket的executor线程池中
                        processSocketAsync(socket,SocketStatus.TIMEOUT);
                    }
                }

                // Loop if endpoint is paused
                while (paused && running) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }

            }
        }
    }


    // --------------------------------------------------- Acceptor Inner Class
    /**
     * The background thread that listens for incoming TCP/IP connections and
     * hands them off to an appropriate processor.
     */
    // 一个线程循环等待socket，然后处理。默认的创建两个请求接收线程
    protected class Acceptor extends AbstractEndpoint.Acceptor {

        @Override
        public void run() {

            int errorDelay = 0;

            // Loop until we receive a shutdown command
            // 让一个线程一直运行的方式是循环，线程外部通过共享变量（这里是running），根据条件停止循环
            // 还有就是暴力停止，即结束进程，或者手动调用线程的stop等方法
            while (running) {

                // Loop if endpoint is paused
                while (paused && running) {
                    state = AcceptorState.PAUSED;
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }

                if (!running) {
                    break;
                }
                state = AcceptorState.RUNNING;

                try {
                    //if we have reached max connections, wait
                    // 当连接数达到最大时，等待 fixme
                    countUpOrAwaitConnection();

                    Socket socket = null;
                    try {
                        // Accept the next incoming connection from the server
                        // socket
                        // 阻塞等待接收客户端请求
                        socket = serverSocketFactory.acceptSocket(serverSocket);
                    } catch (IOException ioe) {
                        countDownConnection();
                        // Introduce delay if necessary
                        errorDelay = handleExceptionWithDelay(errorDelay);
                        // re-throw
                        throw ioe;
                    }
                    // Successful accept, reset the error delay
                    errorDelay = 0;

                    // Configure the socket
                    if (running && !paused && setSocketOptions(socket)) {
                        // Hand this socket off to an appropriate processor
                        // 执行创建的socket
                        if (!processSocket(socket)) {
                            countDownConnection();
                            // Close socket right away
                            closeSocket(socket);
                        }
                    } else {
                        countDownConnection();
                        // Close socket right away
                        closeSocket(socket);
                    }
                } catch (IOException x) {
                    if (running) {
                        log.error(sm.getString("endpoint.accept.fail"), x);
                    }
                } catch (NullPointerException npe) {
                    if (running) {
                        log.error(sm.getString("endpoint.accept.fail"), npe);
                    }
                } catch (Throwable t) {
                    ExceptionUtils.handleThrowable(t);
                    log.error(sm.getString("endpoint.accept.fail"), t);
                }
            }
            state = AcceptorState.ENDED;
        }
    }


    private void closeSocket(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // Ignore
        }
    }


    // ------------------------------------------- SocketProcessor Inner Class


    /**
     * This class is the equivalent of the Worker, but will simply use in an
     * external Executor thread pool.
     * 封装socket对象，用于解析socket
     */
    protected class SocketProcessor implements Runnable {

        protected SocketWrapper<Socket> socket = null;
        protected SocketStatus status = null;

        public SocketProcessor(SocketWrapper<Socket> socket) {
            if (socket==null) throw new NullPointerException();
            this.socket = socket;
        }

        public SocketProcessor(SocketWrapper<Socket> socket, SocketStatus status) {
            this(socket);
            this.status = status;
        }

        @Override
        public void run() {
            boolean launch = false;
            // 对socket枷锁，保证只能有一个线程能处理这个socket
            synchronized (socket) {
                try {
                    SocketState state = SocketState.OPEN;

                    try {
                        // SSL handshake
                        serverSocketFactory.handshake(socket.getSocket()); // 什么都不做
                    } catch (Throwable t) {
                        ExceptionUtils.handleThrowable(t);
                        if (log.isDebugEnabled()) {
                            log.debug(sm.getString("endpoint.err.handshake"), t);
                        }
                        // Tell to close the socket
                        state = SocketState.CLOSED;
                    }

                    if ((state != SocketState.CLOSED)) {// open
                        if (status == null) { // status == null
                            state = handler.process(socket, SocketStatus.OPEN);// AbstractProtocol.process(); state 变为 close
                        } else { // handler == Http11Protocol$Http11ConnectionHandler
                            // 执行请求
                            state = handler.process(socket,status); // state = closed
                        }
                    }
                    if (state == SocketState.CLOSED) {
                        // Close socket
                        if (log.isTraceEnabled()) {
                            log.trace("Closing socket:"+socket);
                        }
                        countDownConnection();// 进入该方法
                        try {
                            socket.getSocket().close(); // 关闭流
                        } catch (IOException e) {
                            // Ignore
                        }
                    } else if (state == SocketState.OPEN ||
                            state == SocketState.UPGRADING  ||
                            state == SocketState.UPGRADED){
                        socket.setKeptAlive(true);
                        socket.access();
                        launch = true; // 此时才走finally try 逻辑
                    } else if (state == SocketState.LONG) {
                        socket.access();
                        waitingRequests.add(socket);// 长连接，
                    }
                } finally {
                    if (launch) {
                        try {
                            getExecutor().execute(new SocketProcessor(socket, SocketStatus.OPEN));
                        } catch (RejectedExecutionException x) {
                            log.warn("Socket reprocessing request was rejected for:"+socket,x);
                            try {
                                //unable to handle connection at this time
                                handler.process(socket, SocketStatus.DISCONNECT);
                            } finally {
                                countDownConnection();
                            }


                        } catch (NullPointerException npe) {
                            if (running) {
                                log.error(sm.getString("endpoint.launch.fail"),
                                        npe);
                            }
                        }
                    }
                }
            }
            socket = null; // 完成请求
            // Finish up this request
        }

    }


    // -------------------- Public methods --------------------

    @Override
    public void bind() throws Exception {

        // Initialize thread count defaults for acceptor
        if (acceptorThreadCount == 0) {
            acceptorThreadCount = 1;
        }
        // Initialize maxConnections
        if (getMaxConnections() == 0) {
            // User hasn't set a value - use the default
            setMaxConnections(getMaxThreadsExecutor(true));
        }

        if (serverSocketFactory == null) {
            if (isSSLEnabled()) {
                serverSocketFactory =
                    handler.getSslImplementation().getServerSocketFactory(this);
            } else {
                serverSocketFactory = new DefaultServerSocketFactory(this);
            }
        }

        if (serverSocket == null) {
            try {
                if (getAddress() == null) {
                    serverSocket = serverSocketFactory.createSocket(getPort(),
                            getBacklog());
                } else {
                    serverSocket = serverSocketFactory.createSocket(getPort(),
                            getBacklog(), getAddress());
                }
            } catch (BindException orig) {
                String msg;
                if (getAddress() == null)
                    msg = orig.getMessage() + " <null>:" + getPort();
                else
                    msg = orig.getMessage() + " " +
                            getAddress().toString() + ":" + getPort();
                BindException be = new BindException(msg);
                be.initCause(orig);
                throw be;
            }
        }

    }

    @Override
    public void startInternal() throws Exception {

        if (!running) {
            running = true;
            paused = false;

            // Create worker collection
            if (getExecutor() == null) {
                createExecutor();
            }

            initializeConnectionLatch();

            // 创建请求线程
            startAcceptorThreads();

            // Start async timeout thread
            // 创建线程
            Thread timeoutThread = new Thread(new AsyncTimeout(),
                    getName() + "-AsyncTimeout");
            // 设置线程优先级
            timeoutThread.setPriority(threadPriority);
            // 设置线程为后台线程
            timeoutThread.setDaemon(true);
            timeoutThread.start();
        }
    }

    @Override
    public void stopInternal() {
        releaseConnectionLatch();
        if (!paused) {
            pause();
        }
        if (running) {
            running = false;
            unlockAccept();
        }
        shutdownExecutor();
    }

    /**
     * Deallocate APR memory pools, and close server socket.
     */
    @Override
    public void unbind() throws Exception {
        if (running) {
            stop();
        }
        if (serverSocket != null) {
            try {
                if (serverSocket != null)
                    serverSocket.close();
            } catch (Exception e) {
                log.error(sm.getString("endpoint.err.close"), e);
            }
            serverSocket = null;
        }
        handler.recycle();
    }


    @Override
    protected AbstractEndpoint.Acceptor createAcceptor() {
        return new Acceptor();
    }


    /**
     * Configure the socket.
     */
    protected boolean setSocketOptions(Socket socket) {
        try {
            // 1: Set socket options: timeout, linger, etc
            socketProperties.setProperties(socket);
        } catch (SocketException s) {
            //error here is common if the client has reset the connection
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("endpoint.err.unexpected"), s);
            }
            // Close the socket
            return false;
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            log.error(sm.getString("endpoint.err.unexpected"), t);
            // Close the socket
            return false;
        }
        return true;
    }


    /**
     * Process a new connection from a new client. Wraps the socket so
     * keep-alive and other attributes can be tracked and then passes the socket
     * to the executor for processing.
     *
     * @param socket    The socket associated with the client.
     *
     * @return          <code>true</code> if the socket is passed to the
     *                  executor, <code>false</code> if something went wrong or
     *                  if the endpoint is shutting down. Returning
     *                  <code>false</code> is an indication to close the socket
     *                  immediately.
     */
    protected boolean processSocket(Socket socket) {
        // Process the request from this socket
        try {
            // 把 socket 封装成 SocketWrapper
            SocketWrapper<Socket> wrapper = new SocketWrapper<Socket>(socket);
            wrapper.setKeepAliveLeft(getMaxKeepAliveRequests());
            // During shutdown, executor may be null - avoid NPE
            if (!running) {
                return false;
            }
            // SocketProcessor就是待执行的Runnable
            // 默认最大支持200个线程。如果并发超过200，比如几百万，那就需要主动限流，加服务器等的了，否则用户等到的就是卡，请求没反应
            // 请求没反应可能是服务器处理不了直接返回错误，还有就是服务器卡死了，请求出错等
            getExecutor().execute(new SocketProcessor(wrapper));
        } catch (RejectedExecutionException x) {
            log.warn("Socket processing request was rejected for:"+socket,x);
            return false;
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            // This means we got an OOM or similar creating a thread, or that
            // the pool and its queue are full
            log.error(sm.getString("endpoint.process.fail"), t);
            return false;
        }
        return true;
    }


    /**
     * Process an existing async connection. If processing is required, passes
     * the wrapped socket to an executor for processing.
     *
     * @param socket    The socket associated with the client.
     * @param status    Only OPEN and TIMEOUT are used. The others are used for
     *                  Comet requests that are not supported by the BIO (JIO)
     *                  Connector.
     * @return          <code>true</code> if the socket is passed to the
     *                  executor, <code>false</code> if something went wrong.
     *                  Returning <code>false</code> is an indication to close
     *                  the socket immediately.
     */
    public boolean processSocketAsync(SocketWrapper<Socket> socket,
            SocketStatus status) {
        try {
            synchronized (socket) {
                if (waitingRequests.remove(socket)) {
                    // 将socket对象封装成runable，即创建 SocketProcessor 对象
                    SocketProcessor proc = new SocketProcessor(socket,status);
                    ClassLoader loader = Thread.currentThread().getContextClassLoader();
                    try {
                        //threads should not be created by the webapp classloader
                        if (Constants.IS_SECURITY_ENABLED) {
                            PrivilegedAction<Void> pa = new PrivilegedSetTccl(
                                    getClass().getClassLoader());
                            AccessController.doPrivileged(pa);
                        } else {
                            Thread.currentThread().setContextClassLoader(
                                    getClass().getClassLoader());
                        }
                        // During shutdown, executor may be null - avoid NPE null point exception
                        if (!running) {
                            return false;
                        }
                        // 将 proc 放入线程池。
                        getExecutor().execute(proc);
                        //TODO gotta catch RejectedExecutionException and properly handle it
                    } finally {
                        if (Constants.IS_SECURITY_ENABLED) {
                            PrivilegedAction<Void> pa = new PrivilegedSetTccl(loader);
                            AccessController.doPrivileged(pa);
                        } else {
                            Thread.currentThread().setContextClassLoader(loader);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            ExceptionUtils.handleThrowable(t);
            // This means we got an OOM or similar creating a thread, or that
            // the pool and its queue are full
            log.error(sm.getString("endpoint.process.fail"), t);
            return false;
        }
        return true;
    }

    protected ConcurrentLinkedQueue<SocketWrapper<Socket>> waitingRequests =
        new ConcurrentLinkedQueue<SocketWrapper<Socket>>();

    @Override
    protected Log getLog() {
        return log;
    }

    private static class PrivilegedSetTccl implements PrivilegedAction<Void> {

        private ClassLoader cl;

        PrivilegedSetTccl(ClassLoader cl) {
            this.cl = cl;
        }

        @Override
        public Void run() {
            Thread.currentThread().setContextClassLoader(cl);
            return null;
        }
    }

}
