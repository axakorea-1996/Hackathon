import os
import paramiko

ec2_host  = os.environ['EC2_HOST']
ec2_user  = os.environ['EC2_USERNAME']
ec2_key   = os.environ['EC2_SSH_KEY']
pr_number = os.environ['PR_NUMBER']
repo      = os.environ['REPO']
msg       = os.environ['COMMIT_MESSAGE'].replace("'", "").replace('"', '')

# SSH 키 임시 저장
with open('/tmp/ec2_key.pem', 'w') as f:
    f.write(ec2_key)
os.chmod('/tmp/ec2_key.pem', 0o600)

# SSH 접속 후 localhost로 webhook 호출
client = paramiko.SSHClient()
client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
client.connect(
    hostname=ec2_host,
    username=ec2_user,
    key_filename='/tmp/ec2_key.pem'
)

cmd = (
    f'curl -s -w "\\n%{{http_code}}" -X POST http://localhost/webhook/github '
    f'-H "Content-Type: application/json" '
    f'-H "X-GitHub-Event: pull_request" '
    f'-d \'{{"action":"opened",'
    f'"pull_request":{{"number":{pr_number},"title":"{msg}"}},'
    f'"repository":{{"full_name":"{repo}"}}}}\''
)

stdin, stdout, stderr = client.exec_command(cmd)
output = stdout.read().decode()
error  = stderr.read().decode()
lines  = output.strip().split('\n')
http_code = lines[-1] if lines else '000'

print(f"webhook 응답 코드: {http_code}")
if http_code == '200':
    print("✅ webhook 호출 성공")
else:
    print(f"❌ webhook 호출 실패: {http_code}")
    if error:
        print(f"에러: {error}")

client.close()