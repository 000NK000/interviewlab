<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { Sparkles, MessageCircleQuestion, SearchCheck, Mic2, ArrowRight, ShieldCheck } from 'lucide-vue-next'
const props=defineProps({room:{type:Object,required:true},me:{type:Object,required:true}})
const tab=ref('INTERVIEWER'), status=ref(null), events=ref([]), busy=ref(''), error=ref('')
const hintRequest=ref(''), transcript=ref(''), source=ref('TRANSCRIPT')
const hintUsed=computed(()=>events.value.filter(e=>e.kind==='HINT'&&['PENDING','DONE'].includes(e.status)).length)
const visible=computed(()=>events.value.filter(e=>tab.value==='INTERVIEWER'?['HINT','QUESTION'].includes(e.kind):tab.value==='REVIEW'?e.kind==='REVIEW':e.kind==='COMMUNICATION').slice().reverse())
const interviewer=computed(()=>props.room.interviewerId===props.me.id)
async function request(path,options={}) {
  const response=await fetch('/api/ai'+path,{credentials:'same-origin',headers:{'Content-Type':'application/json'},...options})
  const text=await response.text();const data=text?JSON.parse(text):null
  if(!response.ok)throw new Error(data?.error||'Local AI request failed')
  return data
}
async function load(){try{[status.value,events.value]=await Promise.all([request('/status'),request('/rooms/'+props.room.id+'/events')])}catch(e){error.value=e.message}}
async function run(kind,path,body) {
  busy.value=kind;error.value=''
  try {
    const event=await request('/rooms/'+props.room.id+path,{method:'POST',body:JSON.stringify(body||{})})
    events.value=[...events.value,event]
    if(kind==='HINT')hintRequest.value=''
  }catch(e){error.value=e.message;try{events.value=await request('/rooms/'+props.room.id+'/events')}catch{}}
  finally{busy.value=''}
}
function output(event){try{return JSON.parse(event.resultJson||'{}')}catch{return {}}}
function date(value){return value?new Date(value).toLocaleString('en-US', {month:'short',day:'numeric',hour:'numeric',minute:'2-digit'}):''}
watch(()=>props.room.id,load)
onMounted(load)
</script>
<template>
  <section class="ai-panel panel">
    <div class="ai-panel-header"><div class="ai-panel-title"><span class="ai-icon"><Sparkles :size="19"/></span><div><div class="eyebrow dark">LOCAL AI · OPTIONAL</div><h2>Interview assistants</h2><p>Three focused roles, powered by a model running on this computer.</p></div></div><span class="ai-model" :class="{offline:status&&!status.available}"><span class="dot"></span>{{ status?.available?status.model:'Local model offline' }}</span></div>
    <div class="ai-tabs"><button :class="{active:tab==='INTERVIEWER'}" @click="tab='INTERVIEWER'"><MessageCircleQuestion :size="16"/> Interviewer</button><button :class="{active:tab==='REVIEW'}" @click="tab='REVIEW'"><SearchCheck :size="16"/> Code reviewer</button><button :class="{active:tab==='COMMUNICATION'}" @click="tab='COMMUNICATION'"><Mic2 :size="16"/> Communication</button></div>
    <div class="ai-panel-body">
      <p v-if="!status?.available" class="ai-offline">Start Ollama and install the configured model to use these assistants. Your interview room still works without AI.</p>
      <p v-if="error" class="ai-inline-error">{{ error }}</p>
      <template v-if="tab==='INTERVIEWER'"><div class="ai-action"><div><h3>Progressive hints</h3><p>Up to three hints per interview. Each step uses the current shared code and previous hints.</p></div><span class="ai-counter">{{ hintUsed }}/3 used</span></div><div class="ai-hint-controls"><input v-model="hintRequest" maxlength="1000" placeholder="Optional: what are you stuck on?"/><button class="primary compact" :disabled="!!busy||!status?.available||props.room.status!=='LIVE'||hintUsed>=3" @click="run('HINT','/hints',{request:hintRequest})">{{ busy==='HINT'?'Thinking...':'Get next hint' }} <ArrowRight :size="14"/></button></div><div class="ai-action ai-action-border"><div><h3>Code aware follow up</h3><p>Ask one focused question about the code currently in the editor.</p></div><button class="secondary compact" :disabled="!!busy||!status?.available||props.room.status!=='LIVE'" @click="run('QUESTION','/questions')">{{ busy==='QUESTION'?'Thinking...':'Ask a question' }}</button></div></template>
      <template v-else-if="tab==='REVIEW'"><div class="ai-action"><div><h3>Review this solution</h3><p>Checks reasoning, complexity, edge cases and code quality. Uses matching judge results when available.</p></div><button class="primary compact" :disabled="!!busy||!status?.available||!props.room.code||props.room.status==='LIVE'&&!interviewer" @click="run('REVIEW','/review')">{{ busy==='REVIEW'?'Reviewing...':'Review code' }} <ArrowRight :size="14"/></button></div><p class="ai-caption"><ShieldCheck :size="14"/> AI analysis is guidance. Passing custom tests does not prove full correctness.</p></template>
      <template v-else><div class="ai-action"><div><h3>Evaluate communication</h3><p>Paste a locally produced voice transcript or written interview notes. The evaluation uses only the text you provide.</p></div></div><div class="ai-communication"><label>Input type<select v-model="source"><option value="TRANSCRIPT">Voice transcript</option><option value="NOTES">Written notes</option></select></label><label>Transcript or notes<textarea v-model="transcript" rows="7" maxlength="12000" placeholder="Candidate: First I would clarify whether the array may contain duplicates...&#10;Interviewer: What edge cases would you test?"></textarea></label><div><small>{{ transcript.length }} / 12,000 characters · minimum 80</small><button class="primary compact" :disabled="!!busy||!status?.available||props.room.status!=='ENDED'||transcript.trim().length<80" @click="run('COMMUNICATION','/communication',{text:transcript,source})">{{ busy==='COMMUNICATION'?'Evaluating...':'Evaluate communication' }} <ArrowRight :size="14"/></button></div></div><p v-if="props.room.status!=='ENDED'" class="ai-caption">Available after the interview ends.</p></template>
      <p v-if="tab==='COMMUNICATION'" class="ai-caption">Scores reflect what the supplied notes document, not unobserved ability. Verify AI claims against the original notes.</p>
      <div class="ai-events"><div class="ai-events-title">Previous results <span>{{ visible.length }}</span></div><p v-if="!visible.length" class="ai-empty">Results for this assistant will appear here and remain in the room history.</p><article v-for="event in visible" :key="event.id" class="ai-event"><div class="ai-event-meta"><strong>{{ event.kind==='HINT'?'Hint '+event.hintNumber:event.kind==='QUESTION'?'Interviewer question':event.kind==='REVIEW'?'Code review':'Communication evaluation' }}</strong><span>{{ event.model }} · {{ date(event.createdAt) }}</span></div><p v-if="event.status!=='DONE'" class="ai-inline-error">{{ event.status==='PENDING'?'Generating...':'Generation failed. You may retry.' }}</p><template v-else-if="event.kind==='HINT'"><p>{{ output(event).hint }}</p><p class="ai-response-label">Think about</p><p>{{ output(event).reasoningPrompt }}</p><p class="ai-response-label">Next step</p><p>{{ output(event).nextStep }}</p></template><template v-else-if="event.kind==='QUESTION'"><p class="ai-question">{{ output(event).question }}</p><p class="ai-muted">Why this question: {{ output(event).purpose }}</p></template><template v-else-if="event.kind==='REVIEW'"><p><strong>Summary:</strong> {{ output(event).summary }}</p><p><strong>Correctness:</strong> {{ output(event).correctness }}</p><div class="ai-complexity"><span>Time: {{ output(event).timeComplexity }}</span><span>Space: {{ output(event).spaceComplexity }}</span></div><p><strong>Edge cases:</strong> {{ (output(event).edgeCases||[]).join(' · ') }}</p><p><strong>Improvements:</strong> {{ (output(event).improvements||[]).join(' · ') }}</p><p class="ai-muted">Evidence limit: {{ output(event).evidenceLimit }}</p></template><template v-else><div class="ai-scores"><span>Clarification <b>{{ output(event).clarificationScore }}/5</b></span><span>Explanation <b>{{ output(event).explanationScore }}/5</b></span><span>Testing <b>{{ output(event).testingScore }}/5</b></span><span>Collaboration <b>{{ output(event).collaborationScore }}/5</b></span></div><p><strong>Evidence:</strong> {{ output(event).evidence }}</p><p><strong>Strengths:</strong> {{ (output(event).strengths||[]).join(' · ') }}</p><p><strong>Improve:</strong> {{ (output(event).improvements||[]).join(' · ') }}</p></template></article></div>
    </div>
  </section>
</template>
