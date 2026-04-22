import json, os, urllib.request

token  = os.environ['GITHUB_TOKEN_VAL']
repo   = os.environ['REPO']
msg    = os.environ['COMMIT_MESSAGE']
sha    = os.environ['SHA']
actor  = os.environ['ACTOR']
output = os.environ['GITHUB_OUTPUT']

# 열린 PR 전체 조회
req = urllib.request.Request(
    f'https://api.github.com/repos/{repo}/pulls?state=open&head=axakorea-1996:dev&base=main',
    headers={
        'Authorization': f'Bearer {token}',
        'Accept': 'application/vnd.github+json'
    }
)
with urllib.request.urlopen(req) as res:
    prs = json.loads(res.read().decode())

# actor가 작성한 PR인지 body에서 확인
my_pr = None
for pr in prs:
    body = pr.get('body', '') or ''
    if f'**작성자:** {actor}' in body:
        my_pr = pr
        break

if my_pr:
    pr_number = my_pr['number']
    print(f"기존 PR 재사용 (작성자: {actor}): #{pr_number}")
else:
    # 새 PR 생성
    body = {
        'title': msg,
        'head':  'dev',
        'base':  'main',
        'body':  f'## 🚀 자동 생성된 PR\n\n**커밋:** {sha}\n**작성자:** {actor}\n**브랜치:** dev → main'
    }
    req = urllib.request.Request(
        f'https://api.github.com/repos/{repo}/pulls',
        data=json.dumps(body).encode(),
        headers={
            'Authorization': f'Bearer {token}',
            'Accept': 'application/vnd.github+json',
            'Content-Type': 'application/json'
        },
        method='POST'
    )
    try:
        with urllib.request.urlopen(req) as res:
            result = json.loads(res.read().decode())
        pr_number = result['number']
        print(f"PR 생성 완료 (작성자: {actor}): #{pr_number}")
    except urllib.error.HTTPError as e:
        error_body = e.read().decode()
        print(f"PR 생성 실패: {e.code} - {error_body}")
        if e.code == 422:
            print("422 에러 - 기존 PR 재조회")
            req2 = urllib.request.Request(
                f'https://api.github.com/repos/{repo}/pulls?state=open&head=axakorea-1996:dev&base=main',
                headers={
                    'Authorization': f'Bearer {token}',
                    'Accept': 'application/vnd.github+json'
                }
            )
            with urllib.request.urlopen(req2) as res2:
                prs2 = json.loads(res2.read().decode())
            # 422도 actor 기준으로 필터링
            for pr in prs2:
                body_text = pr.get('body', '') or ''
                if f'**작성자:** {actor}' in body_text:
                    pr_number = pr['number']
                    print(f"기존 PR 사용 (작성자: {actor}): #{pr_number}")
                    break
            else:
                print("PR 없음, 스킵")
                pr_number = None
        else:
            raise

if pr_number:
    with open(output, 'a') as f:
        f.write(f"pr_number={pr_number}\n")