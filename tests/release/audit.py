"""Release artifact audit. Reports paths and digests, never credential values."""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import zipfile

root = pathlib.Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument('--commit', required=True)
parser.add_argument('--output', required=True)
parser.add_argument('--jar')
args = parser.parse_args()
assert re.fullmatch('[a-f0-9]{40}', args.commit)

def git(*parts):
    return subprocess.check_output(['git', *parts], cwd=root)

paths = git('ls-tree', '-r', '--name-only', args.commit).decode().splitlines()
private = [p for p in paths if re.search(r'(^|/)(\.env($|\.)|.*\.private\.|.*\.(pem|p12|key)$)', p)
           and not p.endswith('.example')]
assert not private, f'Private artifact paths: {private}'
assert not any(p.startswith('docs/verification/') for p in paths), 'Raw evidence must not enter Git'
runtime = [p for p in paths if p.startswith(('src/main/', 'deploy/'))]
faults = []
credentials = []
for name in runtime:
    text = git('show', args.commit + ':' + name).decode('utf-8', errors='replace')
    if name.startswith('src/main/') and re.search(r'Runtime\.getRuntime\(\)\.halt|forceConsumerCrash|simulateDatabaseDisconnect|skipQualificationCheck', text):
        faults.append(name)
    if re.search(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|(?:ghp_|github_pat_|AKIA)[A-Za-z0-9]{16,}', text):
        credentials.append(name)
assert not faults, f'Production fault injection found: {faults}'
assert not credentials, f'Credential-shaped material found: {credentials}'
for name in ['deploy/.env.example', 'deploy/.env.day21-faults.example']:
    text = git('show', args.commit + ':' + name).decode()
    for line in text.splitlines():
        if not line or line.startswith('#') or '=' not in line:
            continue
        key, value = line.split('=', 1)
        if re.search('PASSWORD|SECRET|TOKEN', key):
            assert 'REPLACE' in value.upper() or '${' in value or not value, f'Non-placeholder secret in {name}: {key}'

migrations = []
for name in paths:
    if name.startswith('src/main/resources/db/migration/') and name.endswith('.sql'):
        data = git('show', args.commit + ':' + name)
        # Day 29/30 must not rewrite any already accepted Day 28 migration.
        assert data == git('show', 'eb3ae20c7c3e9f34a0a3add152842e63b516c8d6:' + name), f'Historical migration changed: {name}'
        migrations.append({'path': name, 'sha256': hashlib.sha256(data).hexdigest()})
assert len(migrations) == 18
spec = json.loads(git('show', args.commit + ':docs/api/openapi.yaml'))
operations = [op for entry in spec['paths'].values() for op in entry.values()]
ids = [op['operationId'] for op in operations]
assert len(ids) == len(set(ids)), 'Duplicate operation IDs'
assert len(ids) > 60
collection = json.loads(git('show', args.commit + ':docs/api/apifox-collection.json'))
assert collection['info']['schema'].endswith('/v2.1.0/collection.json')
pom = git('show', args.commit + ':pom.xml').decode()
assert '<version>1.0.0</version>' in pom and not re.search(r'<version>[^<]*(SNAPSHOT|LATEST|RELEASE)', pom)
jar_result = None
if args.jar:
    jar = pathlib.Path(args.jar)
    with zipfile.ZipFile(jar) as archive:
        names = archive.namelist()
        assert not any('CrashProbe' in n or 'IntegrationTest' in n or 'ReleaseApiContractTest' in n for n in names)
        assert 'BOOT-INF/classes/application-docker.yml' in names
    jar_result = {'sha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'testClassesPackaged': False}
result = {'passed': True, 'sourceCommit': args.commit, 'files': len(paths),
          'runtimeFaultEntrypoints': faults, 'credentialShapeFindings': credentials,
          'privateArtifacts': private, 'migrationsUnchangedFromDay28': migrations,
          'apiOperations': len(ids), 'demoHttpRequests': len(collection['item']), 'jar': jar_result,
          'scope': 'Static checks plus manual config review; not a claim to exhaustive secret detection.'}
output = pathlib.Path(args.output)
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(json.dumps({k: result[k] for k in ['passed', 'sourceCommit', 'files', 'apiOperations', 'demoHttpRequests']}))
