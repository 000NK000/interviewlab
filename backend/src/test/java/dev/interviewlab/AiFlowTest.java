package dev.interviewlab;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@TestPropertySource(properties={
    "spring.datasource.url=jdbc:h2:mem:aiflow;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AiFlowTest {
    @Autowired UserRepo users;
    @Autowired RoomService rooms;
    @Autowired AiService ai;
    @Autowired AiEventRepo events;
    @Autowired ObjectMapper json;
    @MockitoBean LocalModelClient model;
    @Test void hintsAreLimitedAndPersisted() throws Exception {
        when(model.model()).thenReturn("test-local");
        when(model.chat(anyString(),anyString(),anyMap())).thenReturn(json.readTree("{\"hint\":\"Think about ordering\",\"reasoningPrompt\":\"What comes first?\",\"nextStep\":\"Sort the input\"}"));
        UUID interviewer=users.save(new AppUser("hint-a@test.local","A","hash")).id;
        UUID candidate=users.save(new AppUser("hint-b@test.local","B","hash")).id;
        var created=rooms.create(interviewer,new RoomApi.NewRoom("Merge Intervals","Merge overlaps",45));
        UUID id=created.room().id();
        rooms.join(id,candidate,created.inviteToken());
        rooms.transition(id,interviewer,"LIVE");
        assertEquals(1,ai.hint(id,candidate,"first hint").hintNumber);
        assertEquals(2,ai.hint(id,candidate,"second hint").hintNumber);
        assertEquals(3,ai.hint(id,candidate,"third hint").hintNumber);
        assertThrows(ResponseStatusException.class,()->ai.hint(id,candidate,"fourth hint"));
        assertEquals(3,events.countByRoomIdAndKindAndStatusIn(id,"HINT",java.util.List.of("DONE")));
        verify(model,times(3)).chat(anyString(),anyString(),anyMap());
    }
}
