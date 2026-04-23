import os
import paramiko

ec2_host  = os.environ['EC2_HOST']
ec2_user  = os.environ['EC2_USERNAME']
ec2_key   = os.environ['EC2_SSH_KEY']
pr_number = os.environ['PR_NUMBER']
repo      = os.environ['REPO']
msg       = os.environ['COMMIT_MESSAGE'].replace("'", "").replace('"', '')

print(f"EC2_HOST: {ec2_host}")
print(f"EC2_USER: {ec2_user}")
print(f"PR_NUMBER: {pr_number}")

# SSH 키 임시 저장
with open('/tmp/ec2_key.pem', 'w') as f:
    f.write(ec2_key)
    # 키 끝에 개행 확인
    if not ec2_key.endswith('\n'):
        f.write('\n')
os.chmod('/tmp/ec2_key.pem', 0o600)

# SSH 키 내용 확인 (첫줄/마지막줄만)
lines = ec2_key.strip().split('\n')
print(f"SSH 키 첫줄: {lines[0]}")
print(f"SSH 키 마지막줄: {lines[-1]}")
print(f"SSH 키 라인 수: {len(lines)}")

try:
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    print("SSH 접속 시도...")
    client.connect(
        hostname=ec2_host,
        username=ec2_user,
        key_filename='/tmp/ec2_key.pem',
        timeout=30
    )
    print("SSH 접속 성공!")

    cmd = (
        f'curl -s -w "\\n%{{http_code}}" -X POST http://localhost/webhook/github '
        f'-H "Content-Type: application/json" '
        f'-H "X-GitHub-Event: pull_request" '
        f'-d \'{{"action":"opened",'
        f'"pull_request":{{"number":{pr_number},"title":"{msg}"}},'
        f'"repository":{{"full_name":"{repo}"}}}}\''
    )

    print(f"실행 명령어: {cmd}")
    stdin, stdout, stderr = client.exec_command(cmd, timeout=30)
    output = stdout.read().decode()
    error  = stderr.read().decode()
    exit_code = stdout.channel.recv_exit_status()

    print(f"exit_code: {exit_code}")
    print(f"stdout: '{output}'")
    print(f"stderr: '{error}'")

    lines  = output.strip().split('\n')
    http_code = lines[-1] if lines else '000'

    print(f"webhook 응답 코드: {http_code}")
    if http_code == '200':
        print("✅ webhook 호출 성공")
    else:
        print(f"❌ webhook 호출 실패: {http_code}")

    client.close()

except Exception as e:
    print(f"❌ 예외 발생: {type(e).__name__}: {e}")
    import traceback
    traceback.print_exc()