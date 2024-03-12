/*
 * Copyright (c) 2006, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

import com.sun.jdi.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;

/**
 * @test
 * @bug 6459476
 * @summary Test interrupting debuggee with single stepping enable
 * @author jjh
 *
 * @run build TestScaffold VMConnection TargetListener TargetAdapter
 * @run compile -g InterruptHangTest.java
 * @run driver InterruptHangTest precise
 * @run driver InterruptHangTest aggressive
 * @run driver InterruptHangTest remote
 */

/**
 * The debuggee loops in the main thread while the debugger has single stepping
 * enabled for the debuggee's main thread. The main thread is repeatedly
 * interrupted while it is looping. If a long time goes by with the debugger
 * not getting a single step event, the test fails.
 *
 * Interrupts are generated in 3 difference modes:
 *   precise - The debuggee creates a 2nd thread that repeatedly calls
 *       Thread.interrupt() on the main thread, but does so in a controlled
 *       fashion that allows to test to verify that every interrupt is
 *       received and handled.
 *   aggressive - The debuggee creates a 2nd thread that repeatedly calls
 *       Thread.interrupt() on the main thread, but does so at a high pace
 *       and without any coordination with the main thread. Because of
 *       this it is not possible to account for all the interrupts since some
 *       might be done before the previous interrupt is handled.
 *   remote - Much like the "aggressive" mode, except interrupts are remotely
 *       generated by the debugger using ThreadReference.interrupt(). There
 *       is no "precise" mode for remotely generated interrupts since it is
 *       difficult to coordinate between the debugger and debuggee in a
 *       reasonable way.
 */

class InterruptHangTarg {
    static String sync = "sync";
    static int interruptsSent;
    static boolean remoteMode;
    static boolean preciseMode;
    static boolean aggressiveMode;

    private final static int INTERRUPTS_EXPECTED = 200;

    public static void main(String[] args){
        int answer = 0; // number of interrupts answered

        System.out.println("Howdy!");

        remoteMode = "remote".equals(args[0]);
        preciseMode = "precise".equals(args[0]);
        aggressiveMode = "aggressive".equals(args[0]);

        if (!remoteMode && !preciseMode && !aggressiveMode) {
            throw new RuntimeException("Invalid first arg for debuggee: " + args[0]);
        }

        // Create the debuggee interruptor thread if needed.
        Thread interruptorThread;
        if (preciseMode) {
            interruptorThread =
                DebuggeeWrapper.newThread(new PreciseInterruptor(Thread.currentThread()));
            interruptorThread.start();
        } else if (aggressiveMode) {
            interruptorThread =
                DebuggeeWrapper.newThread(new AggressiveInterruptor(Thread.currentThread()));
            interruptorThread.start();
        } else {
            interruptorThread = null; // we are in "remote" interrupt mode
        }

        // Debugger will keep stepping thru this loop
        for (int ii = 0; ii < INTERRUPTS_EXPECTED; ii++) {
            boolean wasInterrupted = false;
            try {
                // Give other thread a chance to interrupt
                Thread.sleep(100);
            } catch (InterruptedException ee) {
                answer++;
                wasInterrupted = true;
                boolean isInterrupted = Thread.currentThread().isInterrupted();
                System.out.println("Debuggee interruptee(" + ii + "): isInterrupted(" + isInterrupted + ")");
                if (preciseMode) {
                    // When Thread.sleep() is interrupted, the interrupted status of the thread
                    // should remain cleared (since InterruptedException was thrown), and the
                    // the next interrupt won't come in until after the sync.notify() below.
                    // Note this is not true for the aggressive and remote modes since
                    // interrupts can come in at any time, even while we are handling
                    // an intrrupt.
                    if (isInterrupted) {
                        throw new RuntimeException("Thread should not have interrupted status set.");
                    }
                    synchronized(InterruptHangTarg.sync) {
                        // Let the interruptor thread know it can start interrupting again
                        sync.notify();
                    }
                }
            }
            // Every Thread.sleep() call should get interrupted
            if (!wasInterrupted) {
                throw new RuntimeException("Thread was never interrupted during sleep: " + ii);
            }
        }

        if (interruptorThread != null) {
            synchronized(InterruptHangTarg.sync) {
                // Kill the interrupter thread
                interruptorThread.interrupt();
            }
        }

        // Print how many times an interrupt was sent. When in remote mode, the RemoteInterruptor
        // is in a different process, so interruptsSent is not updated, therefore we don't
        // print it here. The RemoteInterruptor thread keeps its own count and prints
        // it before it exits.
        if (!remoteMode) {
            System.out.println("interrupts sent: " + interruptsSent);
        }

        // When in precise mode, make sure that every interrupt sent resulted in
        // an InterruptedException. Note the interruptor always ends up sending
        // one extra interrupt at the end.
        if (preciseMode && interruptsSent != answer + 1) {
            throw new RuntimeException("Too many interrupts sent. Sent: " + interruptsSent +
                                       ". Expected to send: " + answer + 1);
        }
        System.out.println("Goodbye from InterruptHangTarg!");
    }

    static class AggressiveInterruptor implements Runnable {
        Thread interruptee;
        AggressiveInterruptor(Thread interruptee) {
            this.interruptee = interruptee;
        }

        public void run() {
            while (true) {
                InterruptHangTarg.interruptsSent++;
                interruptee.interrupt();
                try {
                    Thread.sleep(5);
                } catch (InterruptedException ee) {
                    System.out.println("Debuggee Interruptor: finished after " +
                                       InterruptHangTarg.interruptsSent + " iterrupts");
                    break;
                }
            }
        }
    }

    static class PreciseInterruptor implements Runnable {
        Thread interruptee;
        PreciseInterruptor(Thread interruptee) {
            this.interruptee = interruptee;
        }

        public void run() {
            synchronized(InterruptHangTarg.sync) {
                while (true) {
                    InterruptHangTarg.interruptsSent++;
                    interruptee.interrupt();
                    try {
                        // Wait until the interruptee has handled the interrupt
                        InterruptHangTarg.sync.wait();
                    } catch (InterruptedException ee) {
                        System.out.println("Debuggee Interruptor: finished after " +
                                           InterruptHangTarg.interruptsSent + " iterrupts");
                        break;
                    }
                }
            }
        }
    }
}

    /********** test program **********/

public class InterruptHangTest extends TestScaffold {
    class RemoteInterruptor extends Thread {
        static int interruptsSent;
        ThreadReference interruptee;

        RemoteInterruptor(ThreadReference interruptee) {
            this.interruptee = interruptee;
        }

        public void run() {
            try {
                while (true) {
                    interruptee.interrupt();
                    interruptsSent++;
                    Thread.sleep(5);
                }
            } catch (InterruptedException ee) {
                println("RemoteInterruptor thread: Unexpected Interrupt");
                throw new RuntimeException(ee);
            } catch (IllegalThreadStateException | VMDisconnectedException ee) {
                println("RemoteInterruptor thread: Got expected " + ee.getClass().getSimpleName()
                        + " after " + interruptsSent + " interrupts sent. Exiting.");
            } catch (Throwable ee) {
                println("RemoteInterruptor thread: Got unexpected exception after "
                        + interruptsSent + " interrupts sent. Exiting with exception.");
                ee.printStackTrace(System.out);
                throw new RuntimeException(ee);
            }
        }
    }

    ThreadReference mainThread;
    Thread timerThread;
    String sync = "sync";
    static int nSteps = 0;
    static boolean remoteMode;

    InterruptHangTest (String args[]) {
        super(args);
    }

    public static void main(String[] args)      throws Exception {
        remoteMode = "remote".equals(args[0]);
        new InterruptHangTest(args).startTests();
    }

    /********** event handlers **********/

    public void stepCompleted(StepEvent event) {
        synchronized(sync) {
            nSteps++;
        }
        println("Got StepEvent " + nSteps + " at line " +
                event.location().method() + ":" +
                event.location().lineNumber());
        if (nSteps == 1) {
            timerThread.start();
        }
    }

    /********** test core **********/

    protected void runTests() throws Exception {
        BreakpointEvent bpe = startToMain("InterruptHangTarg");
        mainThread = bpe.thread();
        EventRequestManager erm = vm().eventRequestManager();

        /*
         * Set event requests
         */
        StepRequest request = erm.createStepRequest(mainThread,
                                                    StepRequest.STEP_LINE,
                                                    StepRequest.STEP_OVER);
        request.enable();

        // Will be started by the step event handler
        timerThread = new Thread("test timer") {
                public void run() {
                    int mySteps = 0;
                    float timeoutFactor = Float.parseFloat(System.getProperty("test.timeout.factor", "1.0"));
                    long sleepSeconds = (long)(20 * timeoutFactor);
                    println("Timer watching for steps every " + sleepSeconds + " seconds");
                    while (true) {
                        try {
                            Thread.sleep(sleepSeconds * 1000);
                            synchronized(sync) {
                                println("steps = " + nSteps);
                                if (mySteps == nSteps) {
                                    // no step for a long time
                                    failure("failure: Debuggee appears to be hung (no steps for " + sleepSeconds + "s)");
                                    vm().exit(-1);
                                    break;
                                }
                            }
                            mySteps = nSteps;
                        } catch (InterruptedException ee) {
                            break;
                        }
                    }
                }
            };

        Thread remoteInterruptorThread = null;
        if (remoteMode) {
            // Create a thread to call ThreadReference.interrupt() on the
            // debuggee main thread.
            remoteInterruptorThread = new RemoteInterruptor(mainThread);
            remoteInterruptorThread.setName("RemoteInterruptor");
            remoteInterruptorThread.setDaemon(true);
            remoteInterruptorThread.start();
        }

        /*
         * resume the target listening for events
         */
        listenUntilVMDisconnect();
        if (remoteInterruptorThread != null) {
            remoteInterruptorThread.join();
        }
        timerThread.interrupt();

        /*
         * deal with results of test
         * if anything has called failure("foo") testFailed will be true
         */
        if (!testFailed) {
            println("InterruptHangTest("+ args[0] + "): passed");
        } else {
            throw new Exception("InterruptHangTest("+ args[0] + "): failed");
        }
    }
}
