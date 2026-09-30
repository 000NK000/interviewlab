$base = 'http://localhost:18080'
$suffix = [guid]::NewGuid().ToString('N').Substring(0,8)
$a = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$b = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$c = New-Object Microsoft.PowerShell.Commands.WebRequestSession
function Call($session,$method,$path,$body) {
  $params = @{Uri="$base$path";Method=$method;WebSession=$session;SkipHttpErrorCheck=$true}
  if ($null -ne $body) { $params.ContentType='application/json'; $params.Body=($body | ConvertTo-Json -Depth 10 -Compress) }
  $res=Invoke-WebRequest @params
  return @{status=[int]$res.StatusCode;data= $(if ($res.Content) { $res.Content | ConvertFrom-Json } else { $null })}
}
$u1=Call $a POST /api/auth/register @{email="interviewer-$suffix@example.com";displayName='Interviewer';password='password123'}
$u2=Call $b POST /api/auth/register @{email="candidate-$suffix@example.com";displayName='Candidate';password='password123'}
$u3=Call $c POST /api/auth/register @{email="stranger-$suffix@example.com";displayName='Stranger';password='password123'}
$created=Call $a POST /api/rooms @{title='Two Sum';prompt='Return two indices';durationMinutes=45}
$id=$created.data.room.id
$blocked=Call $c GET "/api/rooms/$id" $null
$joined=Call $b POST "/api/rooms/$id/join" @{inviteToken=$created.data.inviteToken}
$started=Call $a POST "/api/rooms/$id/start" $null
$edit=Call $b PUT "/api/rooms/$id/code" @{code='print(input())';language='python';baseVersion=0}
$stale=Call $a PUT "/api/rooms/$id/code" @{code='print(2)';language='python';baseVersion=0}
$submitted=Call $b POST "/api/rooms/$id/submissions" @{cases=@(@{input='hello';expected='hello'})}
for ($i=0; $i -lt 15; $i++) {
  Start-Sleep -Seconds 1
$result=Call $a GET "/api/submissions/$($submitted.data.id)" $null
  if ($result.data.status -notin @('QUEUED','RUNNING')) { break }
}
$javaCode='public class Main { public static void main(String[] args) { java.util.Scanner s = new java.util.Scanner(System.in); System.out.println(s.nextLine()); } }'
$javaEdit=Call $a PUT "/api/rooms/$id/code" @{code=$javaCode;language='java';baseVersion=1}
$javaSubmission=Call $a POST "/api/rooms/$id/submissions" @{cases=@(@{input='world';expected='world'})}
for ($i=0; $i -lt 15; $i++) {
  Start-Sleep -Seconds 1
  $javaResult=Call $a GET "/api/submissions/$($javaSubmission.data.id)" $null
  if ($javaResult.data.status -notin @('QUEUED','RUNNING')) { break }
}
$ended=Call $a POST "/api/rooms/$id/end" $null
$feedback=Call $a PUT "/api/rooms/$id/feedback" @{score=4;notes='Good communication'}
$revisions=Call $b GET "/api/rooms/$id/revisions" $null
[pscustomobject]@{registered=@($u1.status,$u2.status,$u3.status);created=$created.status;strangerAccess=$blocked.status;joined=$joined.status;started=$started.status;edit=$edit.status;staleEdit=$stale.status;pythonJudge=$result.data.status;javaJudge=$javaResult.data.status;ended=$ended.status;feedback=$feedback.status;revisions=$revisions.data.Count} | ConvertTo-Json -Compress
if ($blocked.status -ne 403 -or $stale.status -ne 409 -or $result.data.status -ne 'PASSED' -or $javaResult.data.status -ne 'PASSED' -or $revisions.data.Count -ne 2) { throw 'Smoke test failed' }
