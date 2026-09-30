package dev.interviewlab;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties={
    "spring.datasource.url=jdbc:h2:mem:durablequeue;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "app.judge-workers=0",
    "app.judge-queue-capacity=2",
    "management.server.port=0"
})
class DurableQueueTest {
    @Autowired UserRepo users;
    @Autowired RoomService rooms;
    @Autowired JudgeService judge;
    @Autowired SubmissionRepo submissions;

    @Test void curatedProblemAndBoundedDurableQueue() {
        for(var spec:ProblemBank.publicSpecs()) {
            String id=(String)spec.get("id");
            assertFalse(ProblemBank.samples(id).isEmpty());
            assertFalse(ProblemBank.hidden(id).isEmpty());
            assertTrue(((String)spec.get("prompt")).contains("Input:"));
        }
        UUID interviewer=users.save(new AppUser("durable-a@test.local","A","hash")).id;
        UUID candidate=users.save(new AppUser("durable-b@test.local","B","hash")).id;
        var created=rooms.create(interviewer,new RoomApi.NewRoom("Two Sum",ProblemBank.get("two-sum").prompt(),45,"two-sum"));
        UUID roomId=created.room().id();
        assertEquals(1,created.room().sampleCases().size());
        rooms.join(roomId,candidate,created.inviteToken());
        rooms.transition(roomId,interviewer,"LIVE");
        rooms.edit(roomId,candidate,new RoomApi.UpdateCode("print('0 1')","python",0));
        Submission first=judge.submit(roomId,candidate,List.of(),"retry-1");
        assertEquals(first.id,judge.submit(roomId,candidate,List.of(),"retry-1").id);
        ResponseStatusException changed=assertThrows(ResponseStatusException.class,()->judge.submit(roomId,candidate,List.of(new RoomApi.TestCase("x","y")),"retry-1"));
        assertEquals(409,changed.getStatusCode().value());
        Submission second=judge.submit(roomId,candidate,List.of());
        assertEquals("QUEUED",submissions.findById(first.id).orElseThrow().status);
        assertEquals("[]",first.casesJson); // hidden cases are not persisted in API-visible submissions
        assertNotEquals(first.id,second.id);
        ResponseStatusException full=assertThrows(ResponseStatusException.class,()->judge.submit(roomId,candidate,List.of()));
        assertEquals(429,full.getStatusCode().value());
        assertEquals(2,submissions.countByStatusIn(List.of("QUEUED")));
        submissions.deleteAll();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var start=new java.util.concurrent.CountDownLatch(1);
            var futures=new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for(int i=0;i<8;i++) futures.add(pool.submit(()->{
                start.await();
                try { judge.submit(roomId,candidate,List.of());return 200; }
                catch(ResponseStatusException e) { return e.getStatusCode().value(); }
            }));
            start.countDown();
            int accepted=0,rejected=0;
            for(var future:futures) { int status=future.get();if(status==200)accepted++;else if(status==429)rejected++;else fail("Unexpected status "+status); }
            assertEquals(2,accepted);assertEquals(6,rejected);
            assertEquals(2,submissions.countByStatusIn(List.of("QUEUED")));
        } catch(Exception e) { throw new AssertionError(e); }
        finally { pool.shutdownNow(); }
    }
}
