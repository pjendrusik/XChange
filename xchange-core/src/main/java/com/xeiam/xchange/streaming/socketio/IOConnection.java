/**
 * Copyright (C) 2012 - 2013, Enno Boland
 * Copyright (C) 2012 - 2013 Xeiam LLC http://xeiam.com
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.xeiam.xchange.streaming.socketio;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Scanner;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Class IOConnection.
 */
class IOConnection implements IOCallback {

  private final Logger log = LoggerFactory.getLogger(IOConnection.class);

  private final ObjectMapper objectMapper = new ObjectMapper();

  public static final String FRAME_DELIMITER = "\ufffd";

  /**
   * The Constant STATE_INIT.
   */
  private static final int STATE_INIT = 0;

  /**
   * The Constant STATE_HANDSHAKE.
   */
  private static final int STATE_HANDSHAKE = 1;

  /**
   * The Constant STATE_CONNECTING.
   */
  private static final int STATE_CONNECTING = 2;

  /**
   * The Constant STATE_READY.
   */
  private static final int STATE_READY = 3;

  /**
   * The Constant STATE_INTERRUPTED.
   */
  private static final int STATE_INTERRUPTED = 4;

  /**
   * The Constant STATE_INVALID.
   */
  private static final int STATE_INVALID = 6;

  /**
   * The state.
   */
  private int state = STATE_INIT;

  /**
   * Socket.io path.
   */
  public static final String SOCKET_IO_1 = "/socket.io/1/";

  /**
   * The SSL socket factory for HTTPS connections
   */
  private static SSLSocketFactory sslSocketFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();

  /**
   * All available connections.
   */
  private static HashMap<String, List<IOConnection>> connections = new HashMap<String, List<IOConnection>>();

  /**
   * The url for this connection.
   */
  private URL url;

  /**
   * The transport for this connection.
   */
  private IOTransport transport;

  /**
   * The connection timeout.
   */
  private final int connectTimeout = 10000;

  /**
   * The session id of this connection.
   */
  private String sessionId;

  /**
   * The heartbeat timeout. Set by the server
   */
  private long heartbeatTimeout;

  /**
   * The closing timeout. Set By the server
   */
  private long closingTimeout;

  /**
   * The protocols supported by the server.
   */
  private List<String> protocols;

  /**
   * The output buffer used to cache messages while (re-)connecting.
   */
  private ConcurrentLinkedQueue<String> outputBuffer = new ConcurrentLinkedQueue<String>();

  /**
   * The sockets of this connection.
   */
  private final HashMap<String, SocketIO> sockets = new HashMap<String, SocketIO>();

  /**
   * Custom Request headers used while handshaking
   */
  private final Properties headers;

  /**
   * The first socket to be connected. the socket.io server does not send a connected response to this one.
   */
  private SocketIO firstSocket = null;

  /**
   * The reconnect timer. IOConnect waits a second before trying to reconnect
   */
  final private Timer backgroundTimer = new Timer("backgroundTimer");

  /**
   * A String representation of {@link #url}.
   */
  private String urlStr;

  /**
   * The last occurred exception, which will be given to the user if IOConnection gives up.
   */
  private Exception lastException;

  /**
   * The next ID to use.
   */
  private int nextId = 1;

  /**
   * Acknowledges.
   */
  HashMap<Integer, IOAcknowledge> acknowledge = new HashMap<Integer, IOAcknowledge>();

  /**
   * true if there's already a keepalive in {@link #outputBuffer}.
   */
  private boolean keepAliveInQueue;

  /**
   * The heartbeat timeout task. Only null before connection has been initialised.
   */
  private HearbeatTimeoutTask heartbeatTimeoutTask;

  /**
   * The Class HearbeatTimeoutTask. Handles dropping this IOConnection if no heartbeat is received within life time.
   */
  private class HearbeatTimeoutTask extends TimerTask {

    @Override
    public void run() {

      setState(STATE_INVALID);
      error(new SocketIOException("Timeout Error. No heartbeat from server within life time of the socket. closing.", lastException));
    }
  }

  /**
   * The reconnect task. Null if no reconnection is in progress.
   */
  private ReconnectTask reconnectTask = null;

  /**
   * The Class ReconnectTask. Handles reconnect attempts
   */
  private class ReconnectTask extends TimerTask {

    @Override
    public void run() {

      connectTransport();
      if (!keepAliveInQueue) {
        sendPlain("2::");
        keepAliveInQueue = true;
      }
    }
  }

  /**
   * The Class ConnectThread. Handles connecting to the server with an {@link IOTransport}
   */
  private class ConnectThread extends Thread {

    /**
     * Instantiates a new thread for handshaking/connecting.
     */
    public ConnectThread() {

      super("ConnectThread");
    }

    /**
     * Tries handshaking if necessary and connects with corresponding transport afterwards.
     */
    @Override
    public void run() {

      if (IOConnection.this.getState() == STATE_INIT) {
        handshake();
      }
      connectTransport();
    }

  }

  ;

  /**
   * Set the socket factory used for SSL connections.
   * 
   * @param socketFactory The socket factory
   */
  public static void setDefaultSSLSocketFactory(SSLSocketFactory socketFactory) {

    sslSocketFactory = socketFactory;
  }

  /**
   * Creates a new connection or returns the corresponding one.
   * 
   * @param origin the origin
   * @param socket the socket
   * @return a IOConnection object
   */
  static public IOConnection register(String origin, SocketIO socket) {

    List<IOConnection> list = connections.get(origin);
    if (list == null) {
      list = new LinkedList<IOConnection>();
      connections.put(origin, list);
    } else {
      for (IOConnection connection : list) {
        if (connection.register(socket)) {
          return connection;
        }
      }
    }

    IOConnection connection = new IOConnection(origin, socket);
    list.add(connection);
    return connection;
  }

  /**
   * Connects a socket to the IOConnection.
   * 
   * @param socket the socket to be connected
   * @return true, if successfully registered on this transport, otherwise false.
   */
  public boolean register(SocketIO socket) {

    String namespace = socket.getNamespace();
    if (sockets.containsKey(namespace)) {
      return false;
    }
    sockets.put(namespace, socket);
    socket.setHeaders(headers);
    IOMessage connect = new IOMessage(IOMessage.TYPE_CONNECT, socket.getNamespace(), "");
    sendPlain(connect.toString());
    return true;
  }

  /**
   * Disconnect a socket from the IOConnection. Shuts down this IOConnection if no further connections are available for this IOConnection.
   * 
   * @param socket the socket to be shut down
   */
  public void unregister(SocketIO socket) {

    sendPlain("0::" + socket.getNamespace());
    sockets.remove(socket.getNamespace());
    socket.getCallback().onDisconnect();

    if (sockets.size() == 0) {
      cleanup();
    }
  }

  /**
   * Handshake.
   */
  private void handshake() {

    URL url;
    String response;
    URLConnection connection;
    try {
      setState(STATE_HANDSHAKE);
      url = new URL(this.url.getProtocol() + "://" + this.url.getAuthority() + SOCKET_IO_1 + (this.url.getQuery() == null ? "" : "?" + this.url.getQuery()));
      connection = url.openConnection();
      if (connection instanceof HttpsURLConnection) {
        ((HttpsURLConnection) connection).setSSLSocketFactory(sslSocketFactory);
      }
      connection.setConnectTimeout(connectTimeout);
      connection.setReadTimeout(connectTimeout);

      /* Setting the request headers */
      for (Entry<Object, Object> entry : headers.entrySet()) {
        connection.setRequestProperty((String) entry.getKey(), (String) entry.getValue());
      }
      log.debug("> " + connection.toString());
      InputStream stream = connection.getInputStream();
      Scanner in = new Scanner(stream);
      response = in.nextLine();
      log.debug("< " + response);
      String[] data = response.split(":");
      sessionId = data[0];
      heartbeatTimeout = Long.parseLong(data[1]) * 1000;
      closingTimeout = Long.parseLong(data[2]) * 1000;
      protocols = Arrays.asList(data[3].split(","));
    } catch (Exception e) {
      error(new SocketIOException("Error while handshaking", e));
    }
  }

  /**
   * Connect transport
   */
  private void connectTransport() {

    if (getState() == STATE_INVALID) {
      return;
    }
    setState(STATE_CONNECTING);
    if (protocols.contains(WebSocketTransport.TRANSPORT_NAME)) {
      transport = WebSocketTransport.create(this.url.getProtocol() + "://" + this.url.getAuthority(), this);
    } else if (protocols.contains(XhrTransport.TRANSPORT_NAME)) {
      transport = XhrTransport.create(this.url.getProtocol() + "://" + this.url.getAuthority(), this);
    } else {
      error(new SocketIOException("Server supports no available transports. You should reconfigure the server to support a available transport"));
      return;
    }
    transport.connect();
  }

  /**
   * Creates a new {@link IOAcknowledge} instance which sends its arguments back to the server.
   * 
   * @param message the message
   * @return an {@link IOAcknowledge} instance, may be <code>null</code> if server doesn't request one.
   */
  private IOAcknowledge remoteAcknowledge(IOMessage message) {

    String _id = message.getId();
    if (_id.equals("")) {
      return null;
    } else if (!_id.endsWith("+")) {
      _id = _id + "+";
    }
    final String id = _id;
    final String endPoint = message.getEndpoint();
    return new IOAcknowledge() {

      @Override
      public void ack(Object... args) {

        String jsonString = null;
        try {
          jsonString = objectMapper.writeValueAsString(args);
        } catch (Exception e) {
          error(new SocketIOException("You can only put values in IOAcknowledge.ack() which can be serialized", e));
        }
        IOMessage ackMsg = new IOMessage(IOMessage.TYPE_ACK, endPoint, id + jsonString);
        sendPlain(ackMsg.toString());
      }
    };
  }

  /**
   * adds an {@link IOAcknowledge} to an {@link IOMessage}.
   * 
   * @param message the {@link IOMessage}
   * @param ack the {@link IOAcknowledge}
   */
  private void synthesizeAck(IOMessage message, IOAcknowledge ack) {

    if (ack != null) {
      int id = nextId++;
      acknowledge.put(id, ack);
      message.setId(id + "+");
    }
  }

  /**
   * Instantiates a new IOConnection.
   * 
   * @param url the URL
   * @param socket the socket
   */
  private IOConnection(String url, SocketIO socket) {

    try {
      this.url = new URL(url);
      this.urlStr = url;
    } catch (MalformedURLException e) {
      throw new RuntimeException(e);
    }
    firstSocket = socket;
    headers = socket.getHeaders();
    sockets.put(socket.getNamespace(), socket);
    new ConnectThread().start();
  }

  /**
   * Cleanup. IOConnection is not usable after calling this.
   */
  private void cleanup() {

    setState(STATE_INVALID);
    if (transport != null) {
      transport.disconnect();
    }
    sockets.clear();
    synchronized (connections) {
      List<IOConnection> con = connections.get(urlStr);
      if (con != null && con.size() > 1) {
        con.remove(this);
      } else {
        connections.remove(urlStr);
      }
    }
    log.debug("cleanup");
    backgroundTimer.cancel();
  }

  /**
   * Populates an error to the connected {@link IOCallback}s and shuts down.
   * 
   * @param e an exception
   */
  protected void error(SocketIOException e) {

    // Ensure any errors during disconnect are ignored
    if (state != STATE_INVALID) {

      for (SocketIO socket : sockets.values()) {
        socket.getCallback().onError(e);
      }
      cleanup();
    }
  }

  /**
   * Sends a plain message to the {@link IOTransport}.
   * 
   * @param text the Text to be send.
   */
  private void sendPlain(String text) {

    synchronized (outputBuffer) {
      if (getState() == STATE_READY) {
        try {
          log.debug("> " + text);
          transport.send(text);
        } catch (Exception e) {
          log.debug("IOEx: saving");
          outputBuffer.add(text);
        }
      } else {
        outputBuffer.add(text);
      }
    }
  }

  /**
   * Invalidates an {@link IOTransport}, used for forced reconnecting.
   */
  private void invalidateTransport() {

    if (transport != null) {
      transport.invalidate();
    }
    transport = null;
  }

  /**
   * Reset timeout.
   */
  private void resetTimeout() {

    if (heartbeatTimeoutTask != null) {
      heartbeatTimeoutTask.cancel();
    }
    heartbeatTimeoutTask = new HearbeatTimeoutTask();
    backgroundTimer.schedule(heartbeatTimeoutTask, closingTimeout + heartbeatTimeout);
  }

  /**
   * finds the corresponding callback object to an incoming message. Returns a dummy callback if no corresponding callback can be found
   * 
   * @param message the message
   * @return the iO callback
   * @throws SocketIOException
   */
  private IOCallback findCallback(IOMessage message) throws SocketIOException {

    if ("".equals(message.getEndpoint())) {
      return this;
    }
    SocketIO socket = sockets.get(message.getEndpoint());
    if (socket == null) {
      throw new SocketIOException("Cannot find socket for '" + message.getEndpoint() + "'");
    }
    return socket.getCallback();
  }

  /**
   * Transport connected. {@link IOTransport} calls this when a connection is established.
   */
  public void transportConnected() {

    setState(STATE_READY);
    if (reconnectTask != null) {
      reconnectTask.cancel();
      reconnectTask = null;
    }
    resetTimeout();
    synchronized (outputBuffer) {
      if (transport.canSendBulk()) {
        ConcurrentLinkedQueue<String> outputBuffer = this.outputBuffer;
        this.outputBuffer = new ConcurrentLinkedQueue<String>();
        try {
          // DEBUG
          String[] texts = outputBuffer.toArray(new String[outputBuffer.size()]);
          log.debug("Bulk start:");
          for (String text : texts) {
            log.debug("> " + text);
          }
          log.debug("Bulk end");
          // DEBUG END
          transport.sendBulk(texts);
        } catch (IOException e) {
          this.outputBuffer = outputBuffer;
        }
      } else {
        String text;
        while ((text = outputBuffer.poll()) != null) {
          sendPlain(text);
        }
      }
      this.keepAliveInQueue = false;
    }
  }

  /**
   * Transport disconnected. {@link IOTransport} calls this when a connection has been shut down.
   */
  public void transportDisconnected() {

    log.debug("transportDisconnected called!");

    this.lastException = null;
    setState(STATE_INTERRUPTED);
    reconnect();
  }

  /**
   * Transport error.
   * 
   * @param error the error {@link IOTransport} calls this, when an exception has occurred and the transport is not usable anymore.
   */
  public void transportError(Exception error) {

    this.lastException = error;
    setState(STATE_INTERRUPTED);
    reconnect();
  }

  /**
   * {@link IOTransport} should call this function if it does not support framing. If it does, transportMessage should be used
   * 
   * @param text the text
   */
  public void transportData(String text) {

    if (!text.startsWith(FRAME_DELIMITER)) {
      transportMessage(text);
      return;
    }

    Iterator<String> fragments = Arrays.asList(text.split(FRAME_DELIMITER)).listIterator(1);
    while (fragments.hasNext()) {
      int length = Integer.parseInt(fragments.next());
      String string = fragments.next();
      // Potential BUG: it is not defined if length is in bytes or
      // characters. Assuming characters.

      if (length != string.length()) {
        error(new SocketIOException("Garbage from server: " + text));
        return;
      }

      transportMessage(string);
    }
  }

  /**
   * Transport message. {@link IOTransport} calls this, when a message has been received.
   * 
   * @param text the text
   */
  public void transportMessage(String text) {

    log.debug("< " + text);

    IOMessage message;
    try {
      message = new IOMessage(text);
    } catch (Exception e) {
      error(new SocketIOException("Garbage from server: " + text, e));
      return;
    }
    resetTimeout();
    switch (message.getType()) {
    case IOMessage.TYPE_DISCONNECT:
      try {
        findCallback(message).onDisconnect();
      } catch (Exception e) {
        error(new SocketIOException("Exception was thrown in onDisconnect()", e));
      }
      break;
    case IOMessage.TYPE_CONNECT:
      try {
        if (firstSocket != null && "".equals(message.getEndpoint())) {
          if (firstSocket.getNamespace().equals("")) {
            firstSocket.getCallback().onConnect();
          } else {
            IOMessage connect = new IOMessage(IOMessage.TYPE_CONNECT, firstSocket.getNamespace(), "");
            sendPlain(connect.toString());
          }
        } else {
          findCallback(message).onConnect();
        }
        firstSocket = null;
      } catch (Exception e) {
        error(new SocketIOException("Exception was thrown in onConnect()", e));
      }
      break;
    case IOMessage.TYPE_HEARTBEAT:
      sendPlain("2::");
      break;
    case IOMessage.TYPE_MESSAGE:
      try {
        findCallback(message).onMessage(message.getData(), remoteAcknowledge(message));
      } catch (Exception e) {
        error(new SocketIOException("Exception was thrown in onMessage(String).\n" + "Message was: " + message.toString(), e));
      }
      break;
    case IOMessage.TYPE_JSON_MESSAGE:

      // TODO perhaps try to catch this exception later instead of parsing the json string twice, just trust that it's really JSON.
      try {
        // test if JSON is valid by catching a parse Exception
        objectMapper.readValue(message.getData(), new TypeReference<Map<String, Object>>() {
        });
        findCallback(message).onJSONMessage(message.getData(), remoteAcknowledge(message));
      } catch (JsonParseException e) {
        log.warn("Malformated JSON received: " + message.getData());
      } catch (Exception e) {
        error(new SocketIOException("Exception was thrown in onMessage(JSON).\n" + "Message was: " + message.toString(), e));
      }

      break;
    case IOMessage.TYPE_EVENT:

      try {

        Map<String, Object> map = objectMapper.readValue(message.getData(), new TypeReference<Map<String, Object>>() {
        });

        Object[] argsArray;
        if (map.containsKey("args")) {

          Object argsString = map.get("args");
          List<Object> argObjects = objectMapper.readValue(argsString.toString(), new TypeReference<List<Object>>() {
          });

          argsArray = new Object[argObjects.size()];
          for (int i = 0; i < argObjects.size(); i++) {
            if (argObjects.get(i) != null) {
              argsArray[i] = argObjects.get(i);
            }
          }
        } else {
          argsArray = new Object[0];
        }
        String eventName = map.get("name").toString();
        try {
          findCallback(message).on(eventName, remoteAcknowledge(message), argsArray);
        } catch (Exception e) {
          error(new SocketIOException("Exception was thrown in on(String, JSON[]).\n" + "Message was: " + message.toString(), e));
        }
      } catch (JsonParseException e) {
        log.warn("Malformated JSON received: " + message.getData());
      } catch (JsonMappingException e) {
        log.warn("Mapping JSON received: " + message.getData());
      } catch (IOException e) {
        log.warn("IO Exception: " + message.getData());
      }
      break;

    case IOMessage.TYPE_ACK:

      String[] data = message.getData().split("\\+", 2);
      if (data.length == 2) {
        try {
          int id = Integer.parseInt(data[0]);
          IOAcknowledge ack = acknowledge.get(id);
          if (ack == null) {
            log.warn("Received unknown ack packet");
          } else {

            List<Object> argObjects = objectMapper.readValue(data[1].toString(), new TypeReference<List<Object>>() {
            });
            Object[] argsArray = new Object[argObjects.size()];
            for (int i = 0; i < argObjects.size(); i++) {
              if (argObjects.get(i) != null) {
                argsArray[i] = argObjects.get(i);
              }
            }
            ack.ack(argsArray);

          }
        } catch (NumberFormatException e) {
          log.warn("Received malformated Acknowledge! This is potentially filling up the acknowledges!");
        } catch (JsonParseException e) {
          log.warn("Malformated JSON received: " + message.getData());
        } catch (JsonMappingException e) {
          log.warn("Mapping JSON received: " + message.getData());
        } catch (IOException e) {
          log.warn("IO Exception: " + message.getData());
        }
      } else if (data.length == 1) {
        sendPlain("6:::" + data[0]);
      }
      break;
    case IOMessage.TYPE_ERROR:
      try {
        findCallback(message).onError(new SocketIOException(message.getData()));
      } catch (SocketIOException e) {
        error(e);
      }
      if (message.getData().endsWith("+0")) {
        // We are advised to disconnect
        cleanup();
      }
      break;
    case IOMessage.TYPE_NOOP:
      break;
    default:
      log.warn("Unkown type received" + message.getType());
      break;
    }
  }

  /**
   * Forces a reconnect. This had become useful on some android devices which do not shut down TCP-connections when switching from HSDPA to Wifi
   */
  public void reconnect() {

    log.info("reconnect called!");

    synchronized (this) {
      if (getState() != STATE_INVALID) {
        invalidateTransport();
        setState(STATE_INTERRUPTED);
        if (reconnectTask != null) {
          reconnectTask.cancel();
        }
        reconnectTask = new ReconnectTask();
        try {
          backgroundTimer.schedule(reconnectTask, 1000);
        } catch (IllegalStateException e) {
          // Probably a race condition
          setState(STATE_INVALID);
        }
      }
    }
  }

  /**
   * Returns the session id. This should be called from a {@link IOTransport}
   * 
   * @return the session id to connect to the right Session.
   */
  public String getSessionId() {

    return sessionId;
  }

  /**
   * sends a String message from {@link SocketIO} to the {@link IOTransport}.
   * 
   * @param socket the socket
   * @param ack acknowledge package which can be called from the server
   * @param text the text
   */
  public void send(SocketIO socket, IOAcknowledge ack, String text) {

    IOMessage message = new IOMessage(IOMessage.TYPE_MESSAGE, socket.getNamespace(), text);
    synthesizeAck(message, ack);
    sendPlain(message.toString());
  }

  /**
   * emits an event from {@link SocketIO} to the {@link IOTransport}.
   * 
   * @param socket the socket
   * @param event the event
   * @param ack acknowledge package which can be called from the server
   * @param args the arguments to be send
   */
  public void emit(SocketIO socket, String event, IOAcknowledge ack, Object... args) {

    String jsonString = null;
    try {

      Map<String, Object> map = new HashMap<String, Object>();
      map.put("name", event);
      map.put("args", args);
      jsonString = objectMapper.writeValueAsString(map);

      IOMessage message = new IOMessage(IOMessage.TYPE_EVENT, socket.getNamespace(), jsonString);
      synthesizeAck(message, ack);
      sendPlain(message.toString());

    } catch (Exception e) {
      error(new SocketIOException("Error while emitting an event. Make sure you only try to send arguments, which can be serialized into JSON."));
    }

  }

  /**
   * Checks if IOConnection is currently connected.
   * 
   * @return true, if is connected
   */
  public boolean isConnected() {

    return getState() == STATE_READY;
  }

  /**
   * Gets the current state of this IOConnection.
   * 
   * @return current state
   */
  private synchronized int getState() {

    return state;
  }

  /**
   * Sets the current state of this IOConnection.
   * 
   * @param state the new state
   */
  private synchronized void setState(int state) {

    this.state = state;
  }

  /**
   * gets the currently used transport.
   * 
   * @return currently used transport
   */
  public IOTransport getTransport() {

    return transport;
  }

  @Override
  public void onDisconnect() {

    SocketIO socket = sockets.get("");
    if (socket != null) {
      socket.getCallback().onDisconnect();
    }
  }

  @Override
  public void onConnect() {

    SocketIO socket = sockets.get("");
    if (socket != null) {
      socket.getCallback().onConnect();
    }
  }

  @Override
  public void onMessage(String data, IOAcknowledge ack) {

    for (SocketIO socket : sockets.values()) {
      socket.getCallback().onMessage(data, ack);
    }
  }

  @Override
  public void onJSONMessage(String jsonString, IOAcknowledge ack) {

    for (SocketIO socket : sockets.values()) {
      socket.getCallback().onJSONMessage(jsonString, ack);
    }
  }

  @Override
  public void on(String event, IOAcknowledge ack, Object... args) {

    for (SocketIO socket : sockets.values()) {
      socket.getCallback().on(event, ack, args);
    }
  }

  @Override
  public void onError(SocketIOException socketIOException) {

    for (SocketIO socket : sockets.values()) {
      socket.getCallback().onError(socketIOException);
    }
  }
}
