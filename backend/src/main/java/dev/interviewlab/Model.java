package dev.interviewlab;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

@Entity @Table(name="app_users") class AppUser {
    @Id @GeneratedValue(strategy=GenerationType.UUID) public UUID id;
    @Column(nullable=false, unique=true, length=160) public String email;
    @Column(nullable=false, length=80) public String displayName;
    @Column(nullable=false) public String passwordHash;
    protected AppUser() {}
    AppUser(String email, String displayName, String passwordHash) { this.email=email; this.displayName=displayName; this.passwordHash=passwordHash; }
}

@Entity @Table(name="rooms") class Room {
    @Id @GeneratedValue(strategy=GenerationType.UUID) public UUID id;
    @Column(nullable=false) public UUID interviewerId;
    public UUID candidateId;
    @Column(nullable=false) public String title;
    @Column(length=10000, nullable=false) public String prompt;
    @Column(length=80) public String problemId;
    @Column(nullable=false) public int durationMinutes;
    @Column(nullable=false) public String language="python";
    @Column(length=65535, nullable=false) public String code="";
    @Column(nullable=false) public long version=0;
    @Column(nullable=false) public String status="OPEN";
    @Column(nullable=false) public String inviteHash;
    @Column(nullable=false) public Instant createdAt=Instant.now();
    public Instant startedAt;
    public Instant endedAt;
    protected Room() {}
}

@Entity @Table(name="revisions", indexes=@Index(columnList="roomId,version")) class Revision {
    @Id @GeneratedValue(strategy=GenerationType.UUID) public UUID id;
    @Column(nullable=false) public UUID roomId;
    @Column(nullable=false) public UUID authorId;
    @Column(nullable=false) public long version;
    @Column(length=65535, nullable=false) public String code;
    @Column(nullable=false) public String language;
    @Column(nullable=false) public Instant createdAt=Instant.now();
    protected Revision() {}
    Revision(Room room, UUID authorId) { this.roomId=room.id; this.authorId=authorId; this.version=room.version; this.code=room.code; this.language=room.language; }
}

@Entity @Table(name="feedback") class Feedback {
    @Id @GeneratedValue(strategy=GenerationType.UUID) public UUID id;
    @Column(nullable=false, unique=true) public UUID roomId;
    @Column(nullable=false) public int score;
    @Column(length=5000, nullable=false) public String notes;
    @Column(nullable=false) public Instant createdAt=Instant.now();
    protected Feedback() {}
    Feedback(UUID roomId, int score, String notes) { this.roomId=roomId; this.score=score; this.notes=notes; }
}

@Entity @Table(name="submissions") class Submission {
    @Id @GeneratedValue(strategy=GenerationType.UUID) public UUID id;
    @Column(nullable=false) public UUID roomId;
    @Column(nullable=false) public UUID authorId;
    @Column(length=65535, nullable=false) public String code;
    @Column(nullable=false) public String language;
    @Column(length=10000, nullable=false) public String casesJson;
    @Column(length=64, unique=true) public String requestKey;
    @Column(length=64) public String requestFingerprint;
    @Column(nullable=false) public String status="QUEUED";
    @Column(length=20000) public String resultJson;
    @Column(nullable=false) public Instant createdAt=Instant.now();
    public Instant finishedAt;
    public Instant startedAt;
    public Instant leaseUntil;
    @Column(length=80) public String leaseOwner;
    public Integer attempts=0;
    protected Submission() {}
    Submission(Room room, UUID authorId, String casesJson) { this.roomId=room.id; this.authorId=authorId; this.code=room.code; this.language=room.language; this.casesJson=casesJson; }
}

@Entity @Table(name="judge_queue_gate") class JudgeQueueGate {
    @Id public Integer id;
    protected JudgeQueueGate() {}
    JudgeQueueGate(int id) { this.id=id; }
}

@Entity @Table(name="ai_events", indexes=@Index(columnList="roomId,createdAt")) class AiEvent {
    @Id @GeneratedValue(strategy=GenerationType.UUID) public UUID id;
    @Column(nullable=false) public UUID roomId;
    @Column(nullable=false) public UUID actorId;
    @Column(nullable=false) public String kind;
    @Column(nullable=false) public String status="PENDING";
    public int hintNumber;
    @Column(length=20000) public String inputText;
    @Column(length=20000) public String resultJson;
    @Column(nullable=false) public String model;
    @Column(nullable=false) public Instant createdAt=Instant.now();
    public Instant completedAt;
    protected AiEvent() {}
    AiEvent(UUID roomId,UUID actorId,String kind,int hintNumber,String inputText,String model) {
        this.roomId=roomId;this.actorId=actorId;this.kind=kind;this.hintNumber=hintNumber;this.inputText=inputText;this.model=model;
    }
}

interface UserRepo extends JpaRepository<AppUser,UUID> { Optional<AppUser> findByEmail(String email); }
interface RoomRepo extends JpaRepository<Room,UUID> {
    List<Room> findByInterviewerIdOrCandidateIdOrderByCreatedAtDesc(UUID interviewerId, UUID candidateId);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select r from Room r where r.id = :id")
    Optional<Room> locked(@org.springframework.data.repository.query.Param("id") UUID id);
}
interface RevisionRepo extends JpaRepository<Revision,UUID> { List<Revision> findByRoomIdOrderByVersionAsc(UUID roomId); }
interface FeedbackRepo extends JpaRepository<Feedback,UUID> { Optional<Feedback> findByRoomId(UUID roomId); }
interface SubmissionRepo extends JpaRepository<Submission,UUID> {
    Optional<Submission> findByRequestKey(String requestKey);
    List<Submission> findByRoomIdOrderByCreatedAtDesc(UUID roomId);
    List<Submission> findByStatusIn(Collection<String> statuses);
    long countByStatusIn(Collection<String> statuses);
    @org.springframework.data.jpa.repository.Query("select s.id from Submission s where s.status='QUEUED' or (s.status='RUNNING' and s.leaseUntil < :now) order by s.createdAt asc")
    List<UUID> claimable(@org.springframework.data.repository.query.Param("now") Instant now,org.springframework.data.domain.Pageable page);
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update Submission s set s.status='RUNNING',s.leaseOwner=:owner,s.leaseUntil=:until,s.startedAt=:now,s.attempts=coalesce(s.attempts,0)+1 where s.id=:id and (s.status='QUEUED' or (s.status='RUNNING' and s.leaseUntil < :now))")
    int claim(@org.springframework.data.repository.query.Param("id") UUID id,@org.springframework.data.repository.query.Param("owner") String owner,@org.springframework.data.repository.query.Param("now") Instant now,@org.springframework.data.repository.query.Param("until") Instant until);
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update Submission s set s.leaseUntil=:until where s.id=:id and s.status='RUNNING' and s.leaseOwner=:owner")
    int heartbeat(@org.springframework.data.repository.query.Param("id") UUID id,@org.springframework.data.repository.query.Param("owner") String owner,@org.springframework.data.repository.query.Param("until") Instant until);
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update Submission s set s.status=:status,s.resultJson=:result,s.finishedAt=:finished,s.leaseUntil=null,s.leaseOwner=null where s.id=:id and s.status='RUNNING' and s.leaseOwner=:owner")
    int complete(@org.springframework.data.repository.query.Param("id") UUID id,@org.springframework.data.repository.query.Param("owner") String owner,@org.springframework.data.repository.query.Param("status") String status,@org.springframework.data.repository.query.Param("result") String result,@org.springframework.data.repository.query.Param("finished") Instant finished);
}
interface JudgeQueueGateRepo extends JpaRepository<JudgeQueueGate,Integer> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select g from JudgeQueueGate g where g.id=1")
    Optional<JudgeQueueGate> gate();
}
interface AiEventRepo extends JpaRepository<AiEvent,UUID> {
    List<AiEvent> findByRoomIdOrderByCreatedAtAsc(UUID roomId);
    long countByRoomIdAndKindAndStatusIn(UUID roomId,String kind,Collection<String> statuses);
    List<AiEvent> findByStatus(String status);
}
