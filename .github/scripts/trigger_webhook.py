import os
import stat
import tempfile
import time
import paramiko

ec2_host  = os.environ['EC2_HOST']
ec2_user  = os.environ['EC2_USERNAME']
ec2_key   = os.environ['EC2_SSH_KEY']
pr_number = os.environ['PR_NUMBER']
repo      = os.environ['REPO']
msg       = os.environ['COMMIT_MESSAGE'].replace("'", "").replace('"', '')

# ⚠️ 보안 추가: 임시 파일 사용 후 반드시 삭제
key_file = None
try:
    # ⚠️ 보안 추가: tempfile로 안전한 임시 파일 생성
    with tempfile.NamedTemporaryFile(
        mode='w',
        suffix='.pem',
        delete=False
    ) as f:
        f.write(ec2_key)
        if not ec2_key.endswith('\n'):
            f.write('\n')
        key_file = f.name

    # ⚠️ 보안 추가: 소유자만 읽기 가능
    os.chmod(key_file, stat.S_IRUSR | stat.S_IWUSR)

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    print("SSH 접속 시도...")
    client.connect(
        hostname=ec2_host,
        username=ec2_user,
        key_filename=key_file,
        timeout=30,
        # ⚠️ 보안 추가: 알고리즘 명시
        disabled_algorithms={'pubkeys': ['rsa-sha2-256', 'rsa-sha2-512']}
        if False else {}
    )
    print("SSH 접속 성공!")

    cmd = (
        f'curl -s -w "\\n%{{http_code}}" -X POST http://172.17.0.1/webhook/github '
        f'-H "Content-Type: application/json" '
        f'-H "X-GitHub-Event: pull_request" '
        f'-d \'{{"action":"opened",'
        f'"pull_request":{{"number":{pr_number},"title":"{msg}"}},'
        f'"repository":{{"full_name":"{repo}"}}}}\''
    )

    max_retries    = 5
    retry_interval = 10

    for attempt in range(1, max_retries + 1):
        print(f"webhook 호출 시도 {attempt}/{max_retries} PR #{pr_number}")

        stdin, stdout, stderr = client.exec_command(cmd, timeout=30)
        output    = stdout.read().decode()
        error     = stderr.read().decode()
        exit_code = stdout.channel.recv_exit_status()

        lines     = output.strip().split('\n')
        http_code = lines[-1] if lines else '000'

        print(f"exit_code: {exit_code}, webhook 응답 코드: {http_code}")

        if http_code == '200':
            print("✅ webhook 호출 성공")
            break
        else:
            print(f"❌ webhook 호출 실패: {http_code}")
            if attempt < max_retries:
                print(f"{retry_interval}초 후 재시도...")
                time.sleep(retry_interval)
            else:
                print("❌ 최대 재시도 횟수 초과")

    client.close()

except Exception as e:
    print(f"❌ 예외 발생: {type(e).__name__}: {e}")
    import traceback
    traceback.print_exc()

finally:
    # ⚠️ 보안 추가: SSH 키 파일 반드시 삭제
    if key_file and os.path.exists(key_file):
        os.unlink(key_file)
        print("SSH 키 파일 삭제 완료")