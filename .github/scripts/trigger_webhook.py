import json, os, urllib.request

ec2_host = os.environ['EC2_HOST']
repo     = os.environ['REPO']
msg      = os.environ['COMMIT_MESSAGE']
pr_num   = int(os.environ['PR_NUMBER'])

body = {
    'action': 'opened',
    'pull_request': {'number': pr_num, 'title': msg},
    'repository': {'full_name': repo}
}
req = urllib.request.Request(
    f'http://{ec2_host}/webhook/github',
    data=json.dumps(body).encode(),
    headers={
        'Content-Type': 'application/json',
        'X-GitHub-Event': 'pull_request'
    },
    method='POST'
)
try:
    with urllib.request.urlopen(req, timeout=10) as res:
        print('webhook 호출 성공:', res.status)
except Exception as e:
    print('webhook 호출 실패 (무시):', e)