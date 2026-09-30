package dev.interviewlab;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service class RoomService {
    private final RoomRepo roomRepo; private final UserRepo users; private final RevisionRepo revisionRepo; private final FeedbackRepo feedbackRepo;
    private final SecureRandom random=new SecureRandom();
    RoomService(RoomRepo roomRepo, UserRepo users, RevisionRepo revisionRepo, FeedbackRepo feedbackRepo) { this.roomRepo=roomRepo;this.users=users;this.revisionRepo=revisionRepo;this.feedbackRepo=feedbackRepo; }
    record RoomView(UUID id,String title,String prompt,String problemId,List<RoomApi.TestCase> sampleCases,int durationMinutes,UUID interviewerId,String interviewerName,UUID candidateId,String candidateName,String language,String code,long version,String status,Instant createdAt,Instant startedAt,Instant endedAt) {}
    record CreatedRoom(RoomView room,String inviteToken) {}
    Room require(UUID id,UUID user) {
        Room room=roomRepo.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Room not found"));
        if (!user.equals(room.interviewerId) && !user.equals(room.candidateId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Not a room participant");
        return room;
    }
    private RoomView view(Room r) { return new RoomView(r.id,r.title,r.prompt,r.problemId,ProblemBank.samples(r.problemId),r.durationMinutes,r.interviewerId,users.findById(r.interviewerId).map(u->u.displayName).orElse("Unknown"),r.candidateId,r.candidateId==null?null:users.findById(r.candidateId).map(u->u.displayName).orElse("Unknown"),r.language,r.code,r.version,r.status,r.createdAt,r.startedAt,r.endedAt); }
    List<RoomView> list(UUID user) { return roomRepo.findByInterviewerIdOrCandidateIdOrderByCreatedAtDesc(user,user).stream().map(this::view).toList(); }
    RoomView get(UUID id,UUID user) { return view(require(id,user)); }
    @Transactional CreatedRoom create(UUID user,RoomApi.NewRoom body) {
        if(body.title().length()>150 || body.prompt().length()>10000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Title or prompt too long");
        byte[] bytes=new byte[24];random.nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        if(body.problemId()!=null && ProblemBank.get(body.problemId())==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown problem");
        Room room=new Room();room.interviewerId=user;room.title=body.title().trim();room.prompt=body.prompt().trim();room.problemId=body.problemId();room.durationMinutes=body.durationMinutes();room.inviteHash=hash(token);
        roomRepo.save(room);return new CreatedRoom(view(room),token);
    }
    @Transactional String refreshInvitation(UUID id,UUID user) {
        Room room=roomRepo.locked(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Room not found"));
        if(!user.equals(room.interviewerId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only interviewer can invite");
        if(!"OPEN".equals(room.status) || room.candidateId!=null) throw new ResponseStatusException(HttpStatus.CONFLICT,"Invitation is no longer available");
        byte[] bytes=new byte[24];random.nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        room.inviteHash=hash(token);return token;
    }
    @Transactional RoomView join(UUID id,UUID user,String token) {
        Room room=roomRepo.locked(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Room not found"));
        if(user.equals(room.interviewerId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Interviewer cannot join as candidate");
        if(room.candidateId!=null && room.candidateId.equals(user)) return view(room);
        if(room.candidateId!=null) throw new ResponseStatusException(HttpStatus.CONFLICT,"This room already has a candidate. Sign in with the account that joined it.");
        if(!"OPEN".equals(room.status)) throw new ResponseStatusException(HttpStatus.CONFLICT,"This invitation is no longer active");
        if(token==null || !MessageDigest.isEqual(hash(token).getBytes(StandardCharsets.US_ASCII),room.inviteHash.getBytes(StandardCharsets.US_ASCII))) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Invalid invitation");
        room.candidateId=user;return view(room);
    }
    @Transactional RoomView transition(UUID id,UUID user,String target) {
        Room room=roomRepo.locked(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Room not found"));
        if(!user.equals(room.interviewerId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only interviewer can change status");
        if("LIVE".equals(target) && "OPEN".equals(room.status) && room.candidateId!=null) { room.status="LIVE";room.startedAt=Instant.now(); }
        else if("ENDED".equals(target) && "LIVE".equals(room.status)) { room.status="ENDED";room.endedAt=Instant.now(); }
        else throw new ResponseStatusException(HttpStatus.CONFLICT,"Invalid room transition");
        return view(room);
    }
    @Transactional RoomView edit(UUID id,UUID user,RoomApi.UpdateCode body) {
        Room room=roomRepo.locked(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Room not found"));
        if(!user.equals(room.interviewerId) && !user.equals(room.candidateId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Not a participant");
        if("ENDED".equals(room.status)) throw new ResponseStatusException(HttpStatus.CONFLICT,"Interview has ended");
        if("OPEN".equals(room.status) && !user.equals(room.interviewerId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Interview has not started");
        if(body.baseVersion()!=room.version) throw new ResponseStatusException(HttpStatus.CONFLICT,"Stale code version");
        if(body.code()==null || body.code().length()>50000 || !List.of("java","python").contains(body.language())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid code or language");
        room.code=body.code();room.language=body.language();room.version++;
        revisionRepo.save(new Revision(room,user));return view(room);
    }
    List<Revision> revisions(UUID id,UUID user) { require(id,user);return revisionRepo.findByRoomIdOrderByVersionAsc(id); }
    Feedback feedback(UUID id,UUID user) { require(id,user);return feedbackRepo.findByRoomId(id).orElse(null); }
    @Transactional Feedback saveFeedback(UUID id,UUID user,RoomApi.NewFeedback body) {
        Room room=require(id,user);
        if(!user.equals(room.interviewerId) || !"ENDED".equals(room.status)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Feedback requires an ended interview and interviewer access");
        if(body.notes().length()>5000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Feedback too long");
        Feedback feedback=feedbackRepo.findByRoomId(id).orElseGet(()->new Feedback(id,body.score(),body.notes().trim()));
        feedback.score=body.score();feedback.notes=body.notes().trim();return feedbackRepo.save(feedback);
    }
    private String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
}
