package org.javacomp.server;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.gson.Gson;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.javacomp.file.FileManager;
import org.javacomp.file.FileManagerImpl;
import org.javacomp.logging.JLogger;
import org.javacomp.server.io.RequestReader;
import org.javacomp.server.io.ResponseWriter;
import org.javacomp.server.protocol.DidChangeTextDocumentHandler;
import org.javacomp.server.protocol.DidOpenTextDocumentHandler;
import org.javacomp.server.protocol.ExitHandler;
import org.javacomp.server.protocol.InitializeHandler;
import org.javacomp.server.protocol.ShutdownHandler;

/** Entry point of the JavaComp server. */
public class JavaComp implements Server {
  private static final JLogger logger = JLogger.createForEnclosingClass();

  private static final int REQUEST_BUFFER_SIZE = 4096;
  private static final int NUM_THREADS = 10;

  private final AtomicBoolean isRunning;
  private final RequestParser requestParser;
  private final ResponseWriter responseWriter;
  private final RequestDispatcher requestDispatcher;
  private final Gson gson;

  private boolean initialized;
  private int exitCode = 0;
  private ExecutorService executor;
  private FileManager fileManager;

  public JavaComp(InputStream inputStream, OutputStream outputStream) {
    this.gson = GsonUtils.getGson();
    this.isRunning = new AtomicBoolean(true);
    this.requestParser =
        new RequestParser(this.gson, new RequestReader(inputStream, REQUEST_BUFFER_SIZE));
    this.responseWriter = new ResponseWriter(this.gson, outputStream);
    this.requestDispatcher =
        new RequestDispatcher.Builder()
            .setGson(gson)
            .setRequestParser(requestParser)
            .setResponseWriter(responseWriter)
            // Server manipulation
            .registerHandler(new InitializeHandler(this))
            .registerHandler(new ShutdownHandler(this))
            .registerHandler(new ExitHandler(this))
            // Text document manipulation
            .registerHandler(new DidOpenTextDocumentHandler(this))
            .registerHandler(new DidChangeTextDocumentHandler(this))
            .build();
  }

  public int run() {
    synchronized (isRunning) {
      isRunning.set(true);
    }
    while (isRunning.get()) {
      if (!requestDispatcher.dispatchRequest()) {
        exit();
      }
    }
    return exitCode;
  }

  @Override
  public synchronized void initialize(int clientProcessId, URI projectRootUri) {
    checkState(!initialized, "Cannot initialize the server twice in a row.");
    initialized = true;
    executor = Executors.newFixedThreadPool(NUM_THREADS);
    fileManager = new FileManagerImpl(projectRootUri, executor);
    //TODO: Someday we should implement monitoring client process for all major platforms.
  }

  @Override
  public synchronized void shutdown() {
    checkState(initialized, "Shutting down the server without initializing it.");
    initialized = false;
    fileManager.shutdown();
    fileManager = null;
  }

  @Override
  public synchronized void exit() {
    if (!isRunning.get()) {
      return;
    }

    isRunning.set(false);
    if (initialized) {
      logger.warning(new Throwable(), "exit() is called without shutting down the server.");
      exitCode = 1;
    }

    // Close input and stream to stop blocking on incoming requests.
    try {
      requestParser.close();
    } catch (Exception e) {
      logger.warning(e, "Failed to close input stream on exit.");
    }
  }

  @Override
  public synchronized FileManager getFileManager() {
    checkState(initialized, "Server not initialized.");
    return checkNotNull(fileManager);
  }

  public static final void main(String[] args) {
    int exitCode = new JavaComp(System.in, System.out).run();
    System.exit(exitCode);
  }
}