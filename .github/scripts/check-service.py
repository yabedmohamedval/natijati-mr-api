"""Check existing Cloud Run configuration without printing environment values."""
import json
import os
import sys
from urllib.parse import urlparse

service = json.load(open(sys.argv[1]))
containers = service['spec']['template']['spec']['containers']
assert len(containers) == 1, 'Review multi-container deployment explicitly'
variables = {item['name']: item for item in containers[0].get('env', [])}
for name in ('DB_PASSWORD', 'ADMIN_PASSWORD'):
    reference = variables.get(name, {}).get('valueFrom', {}).get('secretKeyRef', {})
    assert reference.get('name') and reference.get('key'), f'{name}: Secret Manager mapping required'
assert service['status'].get('latestReadyRevisionName'), 'An existing ready revision is required'
if len(sys.argv) == 3:
    before = json.load(open(sys.argv[2]))
    assert before['spec']['template']['spec']['containers'][0].get('env', []) == containers[0].get('env', []), 'Environment or secrets changed unexpectedly'
    revision = service['status']['latestCreatedRevisionName']
    assert revision == service['status']['latestReadyRevisionName'], 'New revision is not ready'
    assert containers[0]['image'] == os.environ['DEPLOY_IMAGE'], 'Unexpected image'
    traffic = service['status'].get('traffic', [])
    assert sum(t.get('percent', 0) for t in traffic if t.get('revisionName') == revision) == 100, 'New revision does not receive all traffic'
    url = service['status']['url']
    parsed = urlparse(url)
    assert parsed.scheme == 'https' and parsed.hostname.endswith('.run.app'), 'Unexpected service URL'
    with open(os.environ['GITHUB_ENV'], 'a') as output:
        output.write(f'SERVICE_URL={url}\n')
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as output:
        output.write(f"Commit: `{os.environ['GITHUB_SHA']}`\n\nImage: `{os.environ['DEPLOY_IMAGE']}`\n\nRevision: `{revision}`\n")
        output.write('\nPrevious traffic (for manual rollback):\n```json\n' + json.dumps(before['status'].get('traffic', []), indent=2) + '\n```\n')
print('Cloud Run service checks passed; environment values are not logged.')
