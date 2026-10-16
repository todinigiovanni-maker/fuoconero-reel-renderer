"""Read-only live smoke check of Netflix's public IT release schedule. No credentials."""
import concurrent.futures
import datetime
import io
import json
import urllib.parse
import urllib.request
from pathlib import Path

BASE = 'https://about.netflix.com/api/data/releases?language=it&country=IT'
NOW = datetime.datetime.now(datetime.timezone.utc)
report = {'checked_utc': NOW.isoformat(), 'territory': 'IT', 'source': BASE,
          'scope': 'Public editorial release schedule, not complete catalog or account history',
          'sony_connected': False, 'playback_verified': False, 'collections': []}
recent = []
for collection in (2694103, 2170013):
    page = 1
    count = 0
    pages = 1
    while page <= pages:
        with urllib.request.urlopen(f'{BASE}&collection={collection}&page={page}', timeout=30) as response:
            assert response.status == 200
            data = json.load(response)
        assert data['current'] == page
        pages = data['totalPages']
        assert 0 <= pages <= 8
        for item in data['data']:
            assert item['country'] == 'IT'
            assert item['collection'] == collection
            assert isinstance(item['videoID'], int) and item['videoID'] > 0
            date = datetime.datetime.fromtimestamp(item['startTime'] / 1000, datetime.timezone.utc)
            count += 1
            if NOW - datetime.timedelta(days=30) <= date <= NOW:
                recent.append(item)
        page += 1
    report['collections'].append({'collection': collection, 'pages': pages, 'entries': count})
recent.sort(key=lambda item: -item['startTime'])
report['recent_count'] = len({item['videoID'] for item in recent})

def check(item):
    result = {'netflix_id': item['videoID'], 'link': f"https://www.netflix.com/watch/{item['videoID']}"}
    host = urllib.parse.urlsplit(item['image']).hostname
    assert host and any(host == root or host.endswith('.' + root) for root in ('nflximg.net','nflximg.com','nflxso.net','nflxext.com'))
    try:
        with urllib.request.urlopen(item['image'], timeout=25) as response:
            result['art_status'] = response.status
            result['art_type'] = response.headers.get('Content-Type')
            payload = response.read(4 * 1024 * 1024 + 1)
            assert len(payload) <= 4 * 1024 * 1024
            from PIL import Image
            result['source_dimensions'] = list(Image.open(io.BytesIO(payload)).size)
    except Exception as error:
        result['art_error'] = str(error)
    try:
        with urllib.request.urlopen(result['link'], timeout=25) as response:
            result['link_http_status'] = response.status
            result['link_final_url'] = response.url
    except Exception as error:
        result['link_http_error'] = str(error)
    return result
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
    report['samples'] = list(pool.map(check, recent[:30]))
output = Path(__file__).resolve().parents[1] / 'validation' / 'public-source.json'
output.write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
