package dev.interviewlab;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties={
    "spring.datasource.url=jdbc:h2:mem:roomflow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class RoomFlowTest {
    @Autowired UserRepo users;
    @Autowired RoomService rooms;

    @Test void invitationRolesVersionAndLifecycle() {
        UUID interviewer=users.save(new AppUser("a@test.local","A","hash")).id;
        UUID candidate=users.save(new AppUser("b@test.local","B","hash")).id;
        UUID stranger=users.save(new AppUser("c@test.local","C","hash")).id;
        var created=rooms.create(interviewer,new RoomApi.NewRoom("Two Sum","Find two indices",45));
        UUID id=created.room().id();
        assertThrows(ResponseStatusException.class,()->rooms.get(id,stranger));
        assertThrows(ResponseStatusException.class,()->rooms.join(id,candidate,"wrong"));
        assertThrows(ResponseStatusException.class,()->rooms.refreshInvitation(id,stranger));
        String newerInvitation=rooms.refreshInvitation(id,interviewer);
        assertThrows(ResponseStatusException.class,()->rooms.join(id,candidate,created.inviteToken()));
        assertEquals(candidate,rooms.join(id,candidate,newerInvitation).candidateId());
        ResponseStatusException occupied=assertThrows(ResponseStatusException.class,()->rooms.join(id,stranger,newerInvitation));
        assertTrue(occupied.getReason().contains("already has a candidate"));
        assertThrows(ResponseStatusException.class,()->rooms.transition(id,candidate,"LIVE"));
        assertEquals("LIVE",rooms.transition(id,interviewer,"LIVE").status());
        assertEquals(1,rooms.edit(id,candidate,new RoomApi.UpdateCode("print(1)","python",0)).version());
        assertThrows(ResponseStatusException.class,()->rooms.edit(id,interviewer,new RoomApi.UpdateCode("print(2)","python",0)));
        assertEquals(1,rooms.revisions(id,candidate).size());
        assertEquals("ENDED",rooms.transition(id,interviewer,"ENDED").status());
        assertThrows(ResponseStatusException.class,()->rooms.edit(id,candidate,new RoomApi.UpdateCode("print(2)","python",1)));
        assertThrows(ResponseStatusException.class,()->rooms.saveFeedback(id,candidate,new RoomApi.NewFeedback(4,"Good")));
        assertEquals(4,rooms.saveFeedback(id,interviewer,new RoomApi.NewFeedback(4,"Good")).score);
    }
}
