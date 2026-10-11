package life.mosaic.tensorllm;

/** One-shot asynchronous shutdown. Only a death notification (or already-dead registration) advances. */
final class WorkerRelease {
    interface Endpoint {
        boolean watch(Runnable death)throws Exception;
        void stop()throws Exception;
        void unbind();
        void unwatch();
    }
    interface Queue {
        void post(Runnable action);
        void later(Runnable action,long delayMs);
        void remove(Runnable action);
    }
    interface Result {
        void released(String evidence);
        void failed(String reason);
        void stopError(Exception error);
    }
    private final Endpoint endpoint;private final Queue queue;private final Result result;
    private boolean done;
    private final Runnable timeout=()->fail("timeout");
    WorkerRelease(Endpoint endpoint,Queue queue,Result result){this.endpoint=endpoint;this.queue=queue;this.result=result;}
    void start(){
        queue.later(timeout,8000);
        try {
            // Subscribe before sending stop: otherwise a fast process exit can be missed.
            boolean registered=endpoint.watch(()->queue.post(()->complete("death_notification")));
            if(!registered){complete("already_dead_at_registration");return;}
            if(done)return;
            try{endpoint.stop();}catch(Exception error){result.stopError(error);}
        }catch(Exception error){
            try{endpoint.stop();}catch(Exception ignored){}
            fail("death_registration_failed: "+error);
        }finally{endpoint.unbind();}
    }
    private void complete(String evidence){if(done)return;done=true;cleanup();result.released(evidence);}
    private void fail(String reason){if(done)return;done=true;cleanup();result.failed(reason);}
    void cancel(){if(done)return;done=true;cleanup();}
    private void cleanup(){queue.remove(timeout);endpoint.unwatch();}
}
