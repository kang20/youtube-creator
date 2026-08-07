#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ADR 보고서 규격 검사기.

사용법:
    python3 scripts/validate_adr.py <report.html> [--strict]

--strict 를 주면 경고도 실패로 취급한다.

종료 코드
    0  통과 (오류 없음)
    1  오류 있음
    2  파일을 읽을 수 없음

검사 항목은 SKILL.md 의 "마무리 검증" 체크리스트와 1:1로 대응한다.
사람이 눈으로 확인하기 어려운 것(숫자 정합성, 링크 유효성, 오타 클래스)만
기계가 잡고, 내용 판단은 사람과 에이전트에게 남긴다.
"""

import re
import sys
from html.parser import HTMLParser

VOID = {'meta', 'link', 'br', 'img', 'input', 'hr', 'source', 'area', 'col'}


class Report:
    def __init__(self, text):
        self.raw = text
        a, b = text.find('<style>'), text.find('</style>')
        self.css = text[a:b] if a >= 0 and b > a else ''
        self.body = text[b:] if b > 0 else text
        self.errors = []
        self.warnings = []

    def err(self, msg):
        self.errors.append(msg)

    def warn(self, msg):
        self.warnings.append(msg)


# --------------------------------------------------------------------------
# 1. HTML 구조
# --------------------------------------------------------------------------

class Nesting(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.stack = []
        self.problems = []

    def handle_starttag(self, tag, attrs):
        if tag not in VOID:
            self.stack.append(tag)

    def handle_endtag(self, tag):
        if not self.stack:
            self.problems.append(f'짝 없는 </{tag}>')
            return
        if self.stack[-1] == tag:
            self.stack.pop()
        elif tag in self.stack:
            while self.stack and self.stack[-1] != tag:
                self.problems.append(f'</{self.stack[-1]}> 가 닫히지 않은 채 </{tag}> 를 만남')
                self.stack.pop()
            if self.stack:
                self.stack.pop()
        else:
            self.problems.append(f'열린 적 없는 </{tag}>')


def check_structure(r):
    p = Nesting()
    p.feed(r.raw)
    for prob in p.problems[:10]:
        r.err(f'[구조] {prob}')
    for tag in p.stack[:10]:
        r.err(f'[구조] <{tag}> 가 닫히지 않았다')


# --------------------------------------------------------------------------
# 2. CSS 에 없는 클래스 (오타 탐지)
# --------------------------------------------------------------------------

def check_classes(r):
    if not r.css:
        r.warn('[CSS] <style> 블록을 찾지 못해 클래스 검사를 건너뛴다')
        return
    used = set()
    for group in re.findall(r'class="([^"]+)"', r.body):
        used.update(group.split())
    unknown = sorted(c for c in used if f'.{c}' not in r.css)
    for c in unknown:
        r.err(f'[CSS] 클래스 .{c} 를 쓰는데 스타일 정의가 없다 (오타이거나 컴포넌트 이름을 잘못 씀)')


# --------------------------------------------------------------------------
# 3. 앵커 링크
# --------------------------------------------------------------------------

def check_anchors(r):
    ids = set(re.findall(r'id="([^"]+)"', r.raw))
    for href in set(re.findall(r'href="#([^"]+)"', r.raw)):
        if href not in ids:
            r.err(f'[링크] #{href} 로 가는 링크가 있는데 그런 id 가 없다')


# --------------------------------------------------------------------------
# 4. 결정 번호
# --------------------------------------------------------------------------

def decision_ids(r):
    return re.findall(r'<span class="cue-id">(D-\d+)</span>', r.body)


def check_decisions(r):
    ids = decision_ids(r)
    if not ids:
        r.warn('[결정] cue-id 가 하나도 없다. 결정 블록이 없는 ADR 은 의심스럽다')
        return
    seen = set()
    for d in ids:
        if d in seen:
            r.err(f'[결정] {d} 가 중복된다')
        seen.add(d)
    nums = sorted(int(d.split('-')[1]) for d in seen)
    expected = list(range(1, len(nums) + 1))
    if nums != expected:
        missing = sorted(set(expected) - set(nums))
        extra = sorted(set(nums) - set(expected))
        detail = []
        if missing:
            detail.append('빠진 번호 ' + ', '.join(f'D-{n:02d}' for n in missing))
        if extra:
            detail.append('범위 밖 번호 ' + ', '.join(f'D-{n:02d}' for n in extra))
        r.err(f'[결정] D-NN 번호가 1부터 연속이 아니다 — {" / ".join(detail)}')


# --------------------------------------------------------------------------
# 5. 맨 앞 요약 (digest)
# --------------------------------------------------------------------------

def check_digest(r):
    if 'class="digest"' not in r.body:
        r.warn('[요약] 맨 앞 "채택한 결정" 블록이 없다')
        return

    rows = re.findall(r'<span class="dg-id">(D-\d+)</span>', r.body)
    if not rows:
        r.err('[요약] digest 블록은 있는데 결정 줄이 하나도 없다')
        return

    all_ids = set(decision_ids(r))
    dig_ids = set(rows)

    for d in sorted(dig_ids - all_ids):
        r.err(f'[요약] {d} 가 요약에 있는데 본문에 그런 결정 블록이 없다')

    if len(rows) != len(dig_ids):
        dup = sorted({d for d in rows if rows.count(d) > 1})
        r.err(f'[요약] 중복된 결정 줄: {", ".join(dup)}')

    # 요약에서 빠진 결정은 dg-foot 에서 사유가 밝혀져야 한다
    m = re.search(r'<div class="dg-foot">(.*?)</div>\s*</section>', r.body, re.S)
    foot = m.group(1) if m else ''
    omitted = sorted(all_ids - dig_ids)
    for d in omitted:
        if d not in foot:
            r.err(f'[요약] {d} 가 요약에서 빠졌는데 dg-foot 에 사유가 없다 — '
                  f'보류라면 밝히고, 채택이라면 요약에 넣어야 한다')

    # 개수 정합성
    cnt = re.search(r'<span class="cnt">Adopted · (\d+)</span>', r.body)
    if not cnt:
        r.warn('[요약] "Adopted · N" 배지를 찾지 못했다')
    elif int(cnt.group(1)) != len(rows):
        r.err(f'[요약] 배지는 {cnt.group(1)} 인데 실제 줄은 {len(rows)} 개다')


# --------------------------------------------------------------------------
# 6. 마스트헤드 / 푸터 집계
# --------------------------------------------------------------------------

def check_tally(r):
    def grab(cls, label):
        m = re.search(r'<div class="%s">%s <span>(\d+)</span></div>' % (cls, label), r.body)
        return int(m.group(1)) if m else None

    확정 = grab('t-a', '확정')
    기각 = grab('t-r', '검토 후 기각')
    보류 = grab('t-o', '보류·미해결')
    m = re.search(r'<div>확인 문제 <span>(\d+)</span></div>', r.body)
    문제 = int(m.group(1)) if m else None

    dig_rows = len(re.findall(r'<span class="dg-id">D-\d+</span>', r.body))
    chip_r = len(re.findall(r'chip c-r"', r.body))
    todo = len(re.findall(r'<ol class="todo">.*?</ol>', r.body, re.S))
    todo_items = 0
    for block in re.findall(r'<ol class="todo">(.*?)</ol>', r.body, re.S):
        todo_items += len(re.findall(r'<li>', block))
    quizzes = len(re.findall(r'<div class="quiz"', r.body))

    if 확정 is None:
        r.warn('[집계] 마스트헤드 "확정" 배지를 찾지 못했다')
    elif dig_rows and 확정 != dig_rows:
        r.err(f'[집계] 마스트헤드 확정={확정} 인데 요약 줄은 {dig_rows} 개다')

    if 기각 is not None and 기각 != chip_r:
        r.err(f'[집계] 마스트헤드 기각={기각} 인데 실제 기각 칩은 {chip_r} 개다')

    if 보류 is not None and todo and 보류 != todo_items:
        r.err(f'[집계] 마스트헤드 보류·미해결={보류} 인데 미해결 항목은 {todo_items} 개다')

    if 문제 is not None and 문제 != quizzes:
        r.err(f'[집계] 마스트헤드 확인 문제={문제} 인데 실제 문제는 {quizzes} 개다')

    # 푸터도 같은 숫자여야 한다
    foot = re.search(r'확정 (\d+) / 검토 후 기각 (\d+) / 보류·미해결 (\d+)(?: / 확인 문제 (\d+))?', r.body)
    if foot and 확정 is not None:
        f확정, f기각, f보류 = int(foot.group(1)), int(foot.group(2)), int(foot.group(3))
        if (f확정, f기각, f보류) != (확정, 기각, 보류):
            r.err(f'[집계] 푸터({f확정}/{f기각}/{f보류})와 마스트헤드({확정}/{기각}/{보류})가 다르다')
        if foot.group(4) and 문제 is not None and int(foot.group(4)) != 문제:
            r.err(f'[집계] 푸터 확인 문제={foot.group(4)}, 마스트헤드={문제}')


# --------------------------------------------------------------------------
# 7. 확인 문제
# --------------------------------------------------------------------------

def check_quizzes(r):
    blocks = re.split(r'(?=<div class="quiz")', r.body)[1:]
    for i, blk in enumerate(blocks, 1):
        blk = blk[:blk.find('</div>\n</div>') + 20] if '</div>\n</div>' in blk else blk[:4000]
        m = re.search(r'data-ans="(\d+)"', blk)
        opts = re.findall(r'class="qo" data-i="(\d+)"', blk)
        if not m:
            r.err(f'[문제 {i}] data-ans 가 없다')
            continue
        if not opts:
            r.err(f'[문제 {i}] 보기(.qo)가 없다')
            continue
        ans = int(m.group(1))
        if ans >= len(opts):
            r.err(f'[문제 {i}] data-ans={ans} 인데 보기는 {len(opts)} 개다 (0부터 센다)')
        if len(opts) < 3:
            r.warn(f'[문제 {i}] 보기가 {len(opts)} 개뿐이다. 4개를 권장한다')
        exp = re.search(r'data-exp="([^"]*)"', blk)
        if not exp:
            r.err(f'[문제 {i}] data-exp(해설) 이 없거나 큰따옴표 때문에 속성이 끊겼다')
        elif len(exp.group(1).strip()) < 10:
            r.warn(f'[문제 {i}] 해설이 너무 짧다')

    # data-exp 안의 큰따옴표는 속성을 깨뜨린다
    for frag in re.findall(r'data-exp="[^"]*"[^>]*"', r.body):
        if frag.count('"') > 2:
            r.err('[문제] data-exp 안에 큰따옴표가 들어가 속성이 끊긴 곳이 있다')
            break


# --------------------------------------------------------------------------
# 8. 목차 번호와 섹션 번호 정합
# --------------------------------------------------------------------------

def check_toc(r):
    items = re.findall(r'<li><a href="#(\w+)">([^<]*)</a></li>', r.body)
    if not items:
        r.warn('[목차] 목차 항목을 찾지 못했다')
        return

    m = re.search(r'counter-reset:toc\s*(-?\d+)?', r.css)
    start = int(m.group(1)) if (m and m.group(1)) else 0

    for k, (sid, title) in enumerate(items, 1):
        shown = start + k
        m2 = re.search(r'<section id="%s"[^>]*>\s*<div class="eyebrow">\s*(\d+)' % re.escape(sid), r.body)
        if not m2:
            continue
        eyebrow = int(m2.group(1))
        if eyebrow != shown:
            r.err(f'[목차] "{title}" 는 목차에서 {shown:02d} 로 보이는데 '
                  f'섹션 eyebrow 는 {eyebrow:02d} 다 (counter-reset:toc 값을 조정하라)')

    n_sections = len(re.findall(r'<section id="s\d+"', r.body))
    if n_sections != len(items):
        r.err(f'[목차] 번호가 붙은 섹션은 {n_sections} 개인데 목차 항목은 {len(items)} 개다')


# --------------------------------------------------------------------------
# 9. 내용 규율 (경고)
# --------------------------------------------------------------------------

def _cards(body):
    """선택지 카드를 하나씩 잘라낸다. 카드는 <div class="opt"> 에서 시작해 </ul> 로 끝난다.

    카드 안에 div 가 중첩되어 있어 여는/닫는 태그 짝만으로는 자를 수 없다.
    """
    out = []
    for m in re.finditer(r'<div class="opt">', body):
        end = body.find('</ul>', m.start())
        if end > 0:
            out.append(body[m.start():end])
    return out


def _chip_label(card):
    m = re.search(r'<span class="chip c-\w">([^<]*)</span>', card)
    return m.group(1).strip() if m else ''


def _card_title(card):
    m = re.search(r'<h4>(.*?)</h4>', card, re.S)
    return re.sub(r'<[^>]+>', '', m.group(1)).strip() if m else '(제목 없음)'


def check_discipline(r):
    # ADR 의 핵심 자산은 기각된 선택지다. 하나도 없으면 요약문이지 ADR 이 아니다.
    if 'class="cue-id"' in r.body and 'chip c-r' not in r.body:
        r.warn('[내용] 문서 전체에 기각 칩이 하나도 없다. '
               '검토했다가 버린 안이 기록되지 않았다면 ADR 이 아니라 결론 요약이다')

    # 상태는 칩 라벨이 말한다(클래스는 색만 정한다). 채택 라벨을 기준으로 본다.
    for card in _cards(r.body):
        label = _chip_label(card)
        title = _card_title(card)
        if '<li' not in card:
            r.warn(f'[내용] 선택지 카드 "{title}" 에 근거가 하나도 없다')
            continue
        if '채택' in label and '<li class="c">' not in card:
            r.warn(f'[내용] 채택 카드 "{title}" 에 단점(li.c)이 없다 — '
                   f'대가 없는 선택은 없고, 안 적으면 나중에 그 대가를 만났을 때 문서를 의심하게 된다')

    # 결정 블록마다 이유가 붙어야 한다.
    for m in re.finditer(r'<div class="cue">(.*?)</div>\s*</div>', r.body, re.S):
        blk = m.group(1)
        did = re.search(r'<span class="cue-id">(D-\d+)</span>', blk)
        did = did.group(1) if did else '(번호 미상)'
        # 근거 없는 결정은 ADR 의 존재 이유를 무너뜨린다. 판단 문제가 아니라 규격 위반이다.
        if '<p class="why">' not in blk:
            r.err(f'[내용] {did} 결정 블록에 이유(p.why)가 없다 — '
                  f'근거 없는 결정은 6개월 뒤 재현할 수 없다')
        if '<p class="decision">' not in blk:
            r.err(f'[내용] {did} 결정 블록에 결정 문장(p.decision)이 없다')

    if 'localStorage' in r.body or 'sessionStorage' in r.body:
        r.err('[규약] localStorage/sessionStorage 를 쓰면 안 된다. 점수는 메모리에만 둔다')


# --------------------------------------------------------------------------

CHECKS = [
    ('구조', check_structure),
    ('CSS 클래스', check_classes),
    ('앵커 링크', check_anchors),
    ('결정 번호', check_decisions),
    ('맨 앞 요약', check_digest),
    ('집계 숫자', check_tally),
    ('확인 문제', check_quizzes),
    ('목차 번호', check_toc),
    ('내용 규율', check_discipline),
]


def main():
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    strict = '--strict' in sys.argv
    if not args:
        print(__doc__)
        return 2
    path = args[0]
    try:
        text = open(path, encoding='utf-8').read()
    except OSError as e:
        print(f'파일을 열 수 없다: {e}')
        return 2

    r = Report(text)
    for name, fn in CHECKS:
        try:
            fn(r)
        except Exception as e:                      # 검사기 자체 결함을 숨기지 않는다
            r.err(f'[{name}] 검사 중 예외: {type(e).__name__}: {e}')

    print(f'검사 대상: {path}  ({len(text):,}자)\n')

    if r.errors:
        print(f'오류 {len(r.errors)}건')
        for m in r.errors:
            print(f'  ✗ {m}')
        print()
    if r.warnings:
        print(f'경고 {len(r.warnings)}건')
        for m in r.warnings:
            print(f'  ! {m}')
        print()
    if not r.errors and not r.warnings:
        print('통과. 모든 검사를 만족한다.')
    elif not r.errors:
        print('오류 없음. 경고는 사람이 판단할 것.')

    if r.errors:
        return 1
    if strict and r.warnings:
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
