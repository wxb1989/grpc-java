/*
 * Copyright 2015, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.testing.integration;

import static com.google.common.truth.Truth.assertAbout;
import static io.grpc.testing.DeadlineSubject.deadline;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.util.concurrent.SettableFuture;

import io.grpc.Context;
import io.grpc.Context.CancellableContext;
import io.grpc.Deadline;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.internal.ManagedChannelImpl;
import io.grpc.internal.ServerImpl;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import io.grpc.testing.integration.Messages.SimpleRequest;
import io.grpc.testing.integration.Messages.SimpleResponse;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Integration test for various forms of cancellation and deadline propagation.
 */
@RunWith(JUnit4.class)
public class CascadingTest {

  @Mock
  TestServiceGrpc.TestServiceImplBase service;
  private ManagedChannelImpl channel;
  private ServerImpl server;
  private CountDownLatch observedCancellations;
  private CountDownLatch receivedCancellations;
  private TestServiceGrpc.TestServiceBlockingStub blockingStub;
  private TestServiceGrpc.TestServiceStub asyncStub;
  private TestServiceGrpc.TestServiceFutureStub futureStub;
  private ExecutorService otherWork;

  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);
    Mockito.when(service.bindService()).thenCallRealMethod();
    // Use a cached thread pool as we need a thread for each blocked call
    otherWork = Executors.newCachedThreadPool();
    channel = InProcessChannelBuilder.forName("channel").executor(otherWork).build();
    blockingStub = TestServiceGrpc.newBlockingStub(channel);
    asyncStub = TestServiceGrpc.newStub(channel);
    futureStub = TestServiceGrpc.newFutureStub(channel);
  }

  @After
  public void tearDown() {
    channel.shutdownNow();
    server.shutdownNow();
    otherWork.shutdownNow();
  }

  /**
   * Test {@link Context} cancellation propagates from the first node in the call chain all the way
   * to the last.
   */
  @Test
  public void testCascadingCancellationViaOuterContextCancellation() throws Exception {
    observedCancellations = new CountDownLatch(2);
    receivedCancellations = new CountDownLatch(3);
    Future<?> chainReady = startChainingServer(3);
    CancellableContext context = Context.current().withCancellation();
    Future<SimpleResponse> future;
    Context prevContext = context.attach();
    try {
      future = futureStub.unaryCall(SimpleRequest.getDefaultInstance());
    } finally {
      context.detach(prevContext);
    }
    chainReady.get(5, TimeUnit.SECONDS);

    context.cancel(null);
    try {
      future.get(5, TimeUnit.SECONDS);
      fail("Expected cancellation");
    } catch (ExecutionException ex) {
      Status status = Status.fromThrowable(ex);
      assertEquals(Status.Code.CANCELLED, status.getCode());

      // Should have observed 2 cancellations responses from downstream servers
      if (!observedCancellations.await(5, TimeUnit.SECONDS)) {
        fail("Expected number of cancellations not observed by clients");
      }
      if (!receivedCancellations.await(5, TimeUnit.SECONDS)) {
        fail("Expected number of cancellations to be received by servers not observed");
      }
    }
  }

  /**
   * Test that cancellation via call cancellation propagates down the call.
   */
  @Test
  public void testCascadingCancellationViaRpcCancel() throws Exception {
    observedCancellations = new CountDownLatch(2);
    receivedCancellations = new CountDownLatch(3);
    Future<?> chainReady = startChainingServer(3);
    Future<SimpleResponse> future = futureStub.unaryCall(SimpleRequest.getDefaultInstance());
    chainReady.get(5, TimeUnit.SECONDS);

    future.cancel(true);
    assertTrue(future.isCancelled());
    if (!observedCancellations.await(5, TimeUnit.SECONDS)) {
      fail("Expected number of cancellations not observed by clients");
    }
    if (!receivedCancellations.await(5, TimeUnit.SECONDS)) {
      fail("Expected number of cancellations to be received by servers not observed");
    }
  }

  /**
   * Test that when RPC cancellation propagates up a call chain, the cancellation of the parent
   * RPC triggers cancellation of all of its children.
   */
  @Test
  public void testCascadingCancellationViaLeafFailure() throws Exception {
    // All nodes (15) except one edge of the tree (4) will be cancelled.
    observedCancellations = new CountDownLatch(11);
    receivedCancellations = new CountDownLatch(11);
    startCallTreeServer(3);
    try {
      // Use response size limit to control tree nodeCount.
      blockingStub.unaryCall(Messages.SimpleRequest.newBuilder().setResponseSize(3).build());
      fail("Expected abort");
    } catch (StatusRuntimeException sre) {
      // Wait for the workers to finish
      Status status = sre.getStatus();
      // Outermost caller observes ABORTED propagating up from the failing leaf,
      // The descendant RPCs are cancelled so they receive CANCELLED.
      assertEquals(Status.Code.ABORTED, status.getCode());

      if (!observedCancellations.await(5, TimeUnit.SECONDS)) {
        fail("Expected number of cancellations not observed by clients");
      }
      if (!receivedCancellations.await(5, TimeUnit.SECONDS)) {
        fail("Expected number of cancellations to be received by servers not observed");
      }
    }
  }

  @Test
  public void testDeadlinePropagation() throws Exception {
    final AtomicInteger recursionDepthRemaining = new AtomicInteger(3);
    final SettableFuture<Deadline> finalDeadline = SettableFuture.create();
    class DeadlineSaver extends TestServiceGrpc.TestServiceImplBase {
      @Override
      public void unaryCall(final SimpleRequest request,
          final StreamObserver<SimpleResponse> responseObserver) {
        Context.currentContextExecutor(otherWork).execute(new Runnable() {
          @Override
          public void run() {
            try {
              if (recursionDepthRemaining.decrementAndGet() == 0) {
                finalDeadline.set(Context.current().getDeadline());
                responseObserver.onNext(SimpleResponse.getDefaultInstance());
              } else {
                responseObserver.onNext(blockingStub.unaryCall(request));
              }
              responseObserver.onCompleted();
            } catch (Exception ex) {
              responseObserver.onError(ex);
            }
          }
        });
      }
    }

    server = InProcessServerBuilder.forName("channel").executor(otherWork)
        .addService(new DeadlineSaver())
        .build().start();

    Deadline initialDeadline = Deadline.after(1, TimeUnit.MINUTES);
    blockingStub.withDeadline(initialDeadline).unaryCall(SimpleRequest.getDefaultInstance());
    assertNotSame(initialDeadline, finalDeadline);
    // Since deadline is re-calculated at each hop, some variance is acceptable and expected.
    assertAbout(deadline())
        .that(finalDeadline.get()).isWithin(1, TimeUnit.SECONDS).of(initialDeadline);
  }

  /**
   * Create a chain of client to server calls which can be cancelled top down.
   *
   * @return a Future that completes when call chain is created
   */
  private Future<?> startChainingServer(final int depthThreshold) throws IOException {
    final AtomicInteger serversReady = new AtomicInteger();
    final SettableFuture<Void> chainReady = SettableFuture.create();
    class ChainingService extends TestServiceGrpc.TestServiceImplBase {
      @Override
      public void unaryCall(final SimpleRequest request,
          final StreamObserver<SimpleResponse> responseObserver) {
        ((ServerCallStreamObserver) responseObserver).setOnCancelHandler(new Runnable() {
          @Override
          public void run() {
            receivedCancellations.countDown();
          }
        });
        if (serversReady.incrementAndGet() == depthThreshold) {
          // Stop recursion
          chainReady.set(null);
          return;
        }

        Context.currentContextExecutor(otherWork).execute(new Runnable() {
          @Override
          public void run() {
            try {
              blockingStub.unaryCall(request);
            } catch (StatusRuntimeException e) {
              Status status = e.getStatus();
              if (status.getCode() == Status.Code.CANCELLED) {
                observedCancellations.countDown();
              } else {
                responseObserver.onError(e);
              }
            }
          }
        });
      }
    }

    server = InProcessServerBuilder.forName("channel").executor(otherWork)
        .addService(new ChainingService())
        .build().start();
    return chainReady;
  }

  /**
   * Create a tree of client to server calls where each received call on the server
   * fans out to two downstream calls. Uses SimpleRequest.response_size to limit the nodeCount
   * of the tree. One of the leaves will ABORT to trigger cancellation back up to tree.
   */
  private void startCallTreeServer(int depthThreshold) throws IOException {
    final AtomicInteger nodeCount = new AtomicInteger((2 << depthThreshold) - 1);
    server = InProcessServerBuilder.forName("channel").executor(otherWork).addService(
        ServerInterceptors.intercept(service,
            new ServerInterceptor() {
              @Override
              public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                  final ServerCall<ReqT, RespT> call,
                  Metadata headers,
                  ServerCallHandler<ReqT, RespT> next) {
                // Respond with the headers but nothing else.
                call.sendHeaders(new Metadata());
                call.request(1);
                return new ServerCall.Listener<ReqT>() {
                  @Override
                  public void onMessage(final ReqT message) {
                    Messages.SimpleRequest req = (Messages.SimpleRequest) message;
                    if (nodeCount.decrementAndGet() == 0) {
                      // we are in the final leaf node so trigger an ABORT upwards
                      Context.currentContextExecutor(otherWork).execute(new Runnable() {
                        @Override
                        public void run() {
                          call.close(Status.ABORTED, new Metadata());
                        }
                      });
                    } else if (req.getResponseSize() != 0) {
                      // We are in a non leaf node so fire off two requests
                      req = req.toBuilder().setResponseSize(req.getResponseSize() - 1).build();
                      for (int i = 0; i < 2; i++) {
                        asyncStub.unaryCall(req,
                            new StreamObserver<Messages.SimpleResponse>() {
                              @Override
                              public void onNext(Messages.SimpleResponse value) {
                              }

                              @Override
                              public void onError(Throwable t) {
                                Status status = Status.fromThrowable(t);
                                if (status.getCode() == Status.Code.CANCELLED) {
                                  observedCancellations.countDown();
                                }
                                // Propagate closure upwards.
                                try {
                                  call.close(status, new Metadata());
                                } catch (IllegalStateException t2) {
                                  // Ignore error if already closed.
                                }
                              }

                              @Override
                              public void onCompleted() {
                              }
                            });
                      }
                    }
                  }

                  @Override
                  public void onCancel() {
                    receivedCancellations.countDown();
                  }
                };
              }
            })
    ).build();
    server.start();
  }
}
