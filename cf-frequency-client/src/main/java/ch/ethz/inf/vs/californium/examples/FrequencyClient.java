/*******************************************************************************
 * Copyright (c) 2012, Institute for Pervasive Computing, ETH Zurich.
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the Institute nor the names of its contributors
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE INSTITUTE AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE INSTITUTE OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * 
 * This file is part of the Californium (Cf) CoAP framework.
 ******************************************************************************/
package ch.ethz.inf.vs.californium.examples;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import ch.ethz.inf.vs.californium.coap.GETRequest;
import ch.ethz.inf.vs.californium.coap.Option;
import ch.ethz.inf.vs.californium.coap.OptionNumberRegistry;
import ch.ethz.inf.vs.californium.coap.Request;
import ch.ethz.inf.vs.californium.coap.Response;
import ch.ethz.inf.vs.californium.coap.TokenManager;
import ch.ethz.inf.vs.californium.util.Log;

/**
 * The Class FrequencyClient.
 * 
 * @author Francesco Corazza
 */
public class FrequencyClient {
    
    /**
     * The Class MesureRunnable.
     * 
     * @author Francesco Corazza
     */
    class MesureRunnable implements Runnable {
        private static final int REQUEST_THRESOLD = 10;
        
        // the dimension of the delayMap on the previous measure
        /** The previous map size. */
        private int previousMapSize = 0;
        
        /** The tot requests. */
        private int totRequests = secondsOfTest * requestsPerSecond;
        
        /*
         * (non-Javadoc)
         * @see java.lang.Runnable#run()
         */
        @Override
        public void run() {
            // run until there are requests without a response
            if (totRequests > REQUEST_THRESOLD) {
                // add the number of the request performed in the last time slot
                int requestCompleted = delayMap.size() - previousMapSize;
                // log only if there are responses
                totRequests -= requestCompleted;
                responseSet.add(requestCompleted);
                
                System.out.println("Second " + responseSet.size() + ", requests completed: " + requestCompleted + ", remaining " + totRequests + " requests");
                
                // update the previous value
                previousMapSize = delayMap.size();
            } else {
                // terminate the execution if there are no more responses to
                // wait for
                measureScheduler.shutdownNow();
            }
        }
    }
    
    /**
     * The Class RequestRunnable.
     * 
     * @author Francesco Corazza
     */
    class RequestRunnable implements Runnable {
        // the dimension of the delayMap on the previous measure
        /** The iterations. */
        private volatile Integer iterations = sendBurst ? secondsOfTest : secondsOfTest * requestsPerSecond;
        
        /*
         * (non-Javadoc)
         * @see java.lang.Runnable#run()
         */
        @Override
        public void run() {
            boolean proceed = false;
            
            synchronized (this) {
                if (iterations > 0) {
                    iterations--;
                    proceed = true;
                }
            }
            
            if (proceed) {
                if (sendBurst) {
                    for (int j = 0; j < requestsPerSecond; j++) {
                        getResponse();
                    }
                } else {
                    getResponse();
                }
            }
            
            // terminate the executor
            if (iterations == 0) {
                requestScheduler.shutdownNow();
            }
        }
        
        /**
         * Gets the response.
         * 
         * @return the response
         */
        private void getResponse() {
            // create request according to specified method
            // Request request = new Request(CodeRegistry.METHOD_GET, false) {
            Request request = new GETRequest() {
                @Override
                protected void handleResponse(Response response) {
                    // response.prettyPrint();
                    
                    delayMap.put(response.getMID(), response.getRTT());
                    
                    // System.out.println("Time elapsed (ms): "
                    // + response.getRTT());
                }
            };
            
            if (testProxy) {
                request.setURI(proxyUri);
                request.setOption(proxyUriOption);
            } else {
                request.setURI(serverUri);
            }
            request.setToken(TokenManager.getInstance().acquireToken());
            
            // execute request
            try {
                request.execute();
            } catch (UnknownHostException e) {
                System.err.println("Unknown host: " + e.getMessage());
                // System.exit(-1);
            } catch (IOException e) {
                System.err.println("Failed to execute request: " + e.getMessage());
                // System.exit(-1);
            }
        }
    }
    
    /*
     * Main method of this client.
     */
    /**
     * The main method.
     * 
     * @param args the arguments
     */
    public static void main(String[] args) {
        Log.setLevel(Level.SEVERE);
        Log.init();
        
        FrequencyClient frequencyClient = new FrequencyClient();
        
        frequencyClient.start();
        
        while (!frequencyClient.isTerminated()) {
        }
        
        frequencyClient.printStats();
        
        System.exit(0);
    }
    
    // maps for logging purpose
    /** The delay map. */
    private final ConcurrentHashMap<Integer, Double> delayMap = new ConcurrentHashMap<Integer, Double>();
    /** The response set. */
    private final List<Integer> responseSet = Collections.synchronizedList(new LinkedList<Integer>());
    
    // parameters
    /** The seconds of test. */
    private int secondsOfTest = 20;
    /** The requests per second. */
    private int requestsPerSecond = 100;
    /** The send burst. */
    private boolean sendBurst = false;
    /** The test proxy. */
    private boolean testProxy = false;
    
    // resources
    /** The server uri. */
    private String serverUri = "coap://localhost:5684/timeResource";
    /** The proxy uri. */
    private String proxyUri = "coap://localhost/proxy";
    /** The proxy uri option. */
    private Option proxyUriOption = new Option("coap://localhost:5684/timeResource", OptionNumberRegistry.PROXY_URI);
    
    // executors
    /** The request scheduler. */
    private final ScheduledExecutorService requestScheduler = Executors.newScheduledThreadPool(secondsOfTest);
    /** The measure scheduler. */
    private final ScheduledExecutorService measureScheduler = Executors.newSingleThreadScheduledExecutor();
    
    /**
     * Instantiates a new frequency client.
     */
    public FrequencyClient() {
    }
    
    /**
     * Checks if is terminated.
     * 
     * @return true, if is terminated
     */
    public boolean isTerminated() {
        return requestScheduler.isTerminated() && measureScheduler.isTerminated();
    }
    
    /**
     * Prints the stats.
     */
    public void printStats() {
        // remove the first measure in order not to have wrong numbers
        responseSet.remove(new Integer(0));
        
        // DURATION
        System.out.println("Duration expected: " + secondsOfTest + "s, actual duration: " + responseSet.size() + "s");
        
        // RESPONSE TIME
        System.out.println("*** RESPONSE TIME ***");
        double count = 0;
        for (Double rtt : delayMap.values()) {
            count += rtt;
        }
        double avg = count / delayMap.size();
        System.out.println("AVG expected: " + (double) 1000 / requestsPerSecond + "ms, AVG actual: " + avg + "ms");
        Double max = Collections.max(delayMap.values());
        Double min = Collections.min(delayMap.values());
        System.out.println("Max: " + max + "ms, min: " + min + "ms");
        
        // NUMBER OF RESPONSES
        System.out.println("*** NUMBER OF RESPONSES ***");
        count = 0;
        for (Integer requests : responseSet) {
            count += requests;
        }
        System.out.println("Tot requests: " + requestsPerSecond * secondsOfTest + ", tot responses: " + count);
        System.out.println("Requests per second sent: " + requestsPerSecond + ", AVG response per second received: " + count / responseSet.size());
        int max1 = Collections.max(responseSet);
        int min1 = Collections.min(responseSet);
        System.out.println("Max: " + max1 + ", min: " + min1);
    }
    
    /**
     * Sets the proxy uri.
     * 
     * @param proxyUri the proxyUri to set
     */
    public void setProxyUri(Option proxyUri) {
        proxyUriOption = proxyUri;
    }
    
    /**
     * Sets the requests per second.
     * 
     * @param requestsPerSecond the requestsPerSecond to set
     */
    public void setRequestsPerSecond(int requestsPerSecond) {
        this.requestsPerSecond = requestsPerSecond;
    }
    
    /**
     * Sets the seconds of test.
     * 
     * @param secondsOfTest the secondsOfTest to set
     */
    public void setSecondsOfTest(int secondsOfTest) {
        this.secondsOfTest = secondsOfTest;
    }
    
    /**
     * Sets the send burst.
     * 
     * @param sendBurst the sendBurst to set
     */
    public void setSendBurst(boolean sendBurst) {
        this.sendBurst = sendBurst;
    }
    
    /**
     * Sets the test proxy.
     * 
     * @param testProxy the testProxy to set
     */
    public void setTestProxy(boolean testProxy) {
        this.testProxy = testProxy;
    }
    
    /**
     * Sets the uRI.
     * 
     * @param uRI the uRI to set
     */
    public void setURI(String uRI) {
        serverUri = uRI;
    }
    
    /**
     * Sets the uR i_ proxy.
     * 
     * @param uRI_PROXY the uRI_PROXY to set
     */
    public void setURI_PROXY(String uRI_PROXY) {
        proxyUri = uRI_PROXY;
    }
    
    /**
     * Start.
     */
    public final void start() {
        // start the measurement thread
        measureScheduler.scheduleWithFixedDelay(new MesureRunnable(), 0, 1, TimeUnit.SECONDS);
        
        // start the execution thread for the requests
        long delay = sendBurst ? 1000000 : 1000000 / requestsPerSecond;
        requestScheduler.scheduleWithFixedDelay(new RequestRunnable(), 0, delay, TimeUnit.NANOSECONDS);
        
        // wait the termination of the requests and the measurements
        try {
            requestScheduler.awaitTermination(secondsOfTest * 2, TimeUnit.SECONDS);
            measureScheduler.awaitTermination(secondsOfTest * 2, TimeUnit.SECONDS);
        } catch (InterruptedException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
        
        // finish
        System.out.println();
    }
}
