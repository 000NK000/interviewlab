<script setup>
import { ref, onMounted } from 'vue'
import { Sparkles } from 'lucide-vue-next'
const emit=defineEmits(['selected'])
const difficulty=ref('MEDIUM'), topic=ref(''), status=ref(null), busy=ref(false), error=ref(''), picked=ref(null)
const catalog=ref([]), selectedId=ref('')
async function request(path,options={}) {
  const response=await fetch(path,{credentials:'same-origin',headers:{'Content-Type':'application/json'},...options})
  const text=await response.text();const data=text?JSON.parse(text):null
  if(!response.ok)throw new Error(data?.error||'Request failed')
  return data
}
function chooseCurated() {
  const problem=catalog.value.find(item=>item.id===selectedId.value)
  if(!problem)return
  picked.value=problem
  emit('selected',problem)
}
async function chooseAi() {
  busy.value=true;error.value=''
  try { picked.value=await request('/api/ai/problems',{method:'POST',body:JSON.stringify({difficulty:difficulty.value,topic:topic.value})});emit('selected',picked.value) }
  catch(e){error.value=e.message}finally{busy.value=false}
}
onMounted(async()=>{
  try { catalog.value=await request('/api/problems');selectedId.value=catalog.value[0]?.id||'' } catch(e) { error.value=e.message }
  try { status.value=await request('/api/ai/status') } catch { status.value={available:false} }
})
</script>
<template>
  <section class="ai-picker">
    <div class="ai-picker-head"><span class="ai-icon"><Sparkles :size="18"/></span><div><strong>Choose a tested problem</strong><p>Every curated problem has a stdin/stdout contract, sample case and extra judge cases.</p></div><span class="ai-model" :class="{offline:status&&!status.available}">{{ status?.available?status.model:'Local model offline' }}</span></div>
    <div class="ai-picker-controls"><label>Problem<select v-model="selectedId"><option v-for="item in catalog" :key="item.id" :value="item.id">{{ item.title }}</option></select></label><button class="primary" :disabled="!selectedId" @click="chooseCurated">Use problem</button></div>
    <div class="ai-picker-controls"><label>Difficulty<select v-model="difficulty"><option value="EASY">Easy</option><option value="MEDIUM">Medium</option><option value="HARD">Hard</option></select></label><label>Topic <small>(optional)</small><input v-model="topic" maxlength="50" placeholder="arrays, graphs, heaps..."/></label><button class="primary" :disabled="busy||!status?.available" @click="chooseAi"><Sparkles :size="15"/>{{ busy?'Thinking...':'Let AI choose' }}</button></div>
    <p v-if="error" class="ai-inline-error">{{ error }}</p>
    <p v-if="picked" class="ai-picked"><strong>{{ picked.title }}</strong> selected. {{ picked.openingQuestion?'Opening question: '+picked.openingQuestion:'Sample cases are available in the room.' }}</p>
  </section>
</template>
