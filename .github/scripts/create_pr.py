import json, os, urllib.request

token  = os.environ['GITHUB_TOKEN_VAL']
repo   = os.environ['REPO']
msg    = os.environ['COMMIT_MESSAGE']
sha    = os.environ['SHA']
actor  = os.environ['ACTOR']
output = os.environ['GITHUB_OUTPUT']

# 기존 PR 확인
req = urllib.request.Request(
    f'https://api.github.com/repos/{repo}/pulls?state=open&head=axakorea-1996:dev&base=main',
    headers={
        'Authorization': f'Bearer {token}',
        'Accept': 'application/vnd.github+json'
    }
)
with urllib.request.urlopen(req) as res:
    prs = json.loads(res.read().decode())

if prs:
    pr_number = prs[0]['number']
    print(f"이미 열린 PR 있음: #{pr_number}")
else:
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
    with urllib.request.urlopen(req) as res:
        result = json.loads(res.read().decode())
    pr_number = result['number']
    print(f"PR 생성 완료: #{pr_number}")

with open(output, 'a') as f:
    f.write(f"pr_number={pr_number}\n")