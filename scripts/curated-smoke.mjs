import fs from 'node:fs'
import { templateFor } from '../frontend/src/problemTemplates.js'

const base=process.env.INTERVIEWLAB_API_URL||'http://127.0.0.1:18080'
const out=process.argv[2]
const suffix=Math.random().toString(16).slice(2,10)
const password='Demo!'+suffix+'9'
async function call(method,path,cookie,body,extraHeaders={}) {
  const response=await fetch(base+path,{method,headers:{...(cookie?{Cookie:cookie}:{}),...(body===undefined?{}:{'Content-Type':'application/json'}),...extraHeaders},body:body===undefined?undefined:JSON.stringify(body)})
  const raw=await response.text();const data=raw?JSON.parse(raw):null
  if(!response.ok)throw new Error(`${method} ${path}: ${response.status} ${raw}`)
  return {data,cookie:response.headers.get('set-cookie')?.split(';')[0]}
}
const auth=async(role)=>{
  const result=await call('POST','/api/auth/register',null,{email:`curated-${role}-${suffix}@example.test`,displayName:role,password})
  return result.cookie
}
async function result(id,cookie) {
  const until=Date.now()+90000
  while(Date.now()<until) {
    const item=(await call('GET',`/api/submissions/${id}`,cookie)).data
    if(['PASSED','FAILED','ERROR'].includes(item.status))return item
    await new Promise(resolve=>setTimeout(resolve,250))
  }
  throw new Error('Judge timed out')
}
const interviewer=await auth('interviewer'),candidate=await auth('candidate')
const catalog=(await call('GET','/api/problems',interviewer)).data
const problem=catalog.find(item=>item.id==='two-sum')
if(!problem)throw new Error('Two Sum not in catalog')
const created=(await call('POST','/api/rooms',interviewer,{title:problem.title,prompt:problem.prompt,durationMinutes:45,problemId:problem.id})).data
const room=created.room.id
await call('POST',`/api/rooms/${room}/join`,candidate,{inviteToken:created.inviteToken})
await call('POST',`/api/rooms/${room}/start`,interviewer)
const javaStarter=templateFor('two-sum','java')
const javaCode=javaStarter.replace('return new int[]{-1};','Map<Integer, Integer> seen = new HashMap<>(); for (int j = 0; j < nums.length; j++) { int needed = target - nums[j]; if (seen.containsKey(needed)) return new int[]{seen.get(needed), j}; seen.put(nums[j], j); } return new int[]{-1};')
if(javaCode===javaStarter)throw new Error('Two Sum Java starter changed; update smoke test')
const solutions=[
  {language:'python',code:'n,target=map(int,input().split())\nnums=list(map(int,input().split()))\nseen={}\nfor i,x in enumerate(nums):\n    if target-x in seen:\n        print(seen[target-x],i)\n        break\n    seen[x]=i\nelse:\n    print(-1)\n'},
  {language:'java',code:javaCode}
]
const checks=[]
for(let index=0;index<solutions.length;index++) {
  const solution=solutions[index]
  await call('PUT',`/api/rooms/${room}/code`,candidate,{...solution,baseVersion:index})
  const key=`curated-${suffix}-${solution.language}`
  const submitted=(await call('POST',`/api/rooms/${room}/submissions`,candidate,{cases:[]},{'Idempotency-Key':key})).data
  const repeated=(await call('POST',`/api/rooms/${room}/submissions`,candidate,{cases:[]},{'Idempotency-Key':key})).data
  if(submitted.id!==repeated.id)throw new Error('Idempotent retry created a second submission')
  const finished=await result(submitted.id,candidate)
  const cases=JSON.parse(finished.resultJson||'{}').cases||[]
  if(finished.status!=='PASSED'||cases.length!==3||cases.some(item=>!item.passed))throw new Error(`${solution.language} failed: ${finished.resultJson}`)
  if(cases.filter(item=>item.hidden).length!==2||cases.some(item=>item.hidden&&('input' in item||'expected' in item||'actual' in item)))throw new Error('Hidden test information leaked')
  checks.push({language:solution.language,status:finished.status,cases:cases.length,hidden:2,idempotentRetry:true})
}
const report={room,problem:problem.id,checks,passed:true,time:new Date().toISOString()}
if(out)fs.writeFileSync(out,JSON.stringify(report,null,2)+'\n')
console.log(JSON.stringify(report,null,2))
