# 컴포넌트 레퍼런스

`assets/template.html`에 모든 CSS가 들어 있다. 아래 조각을 그대로 붙여 쓴다.
클래스명을 임의로 바꾸지 않는다 — CSS에 정의된 것만 동작한다.

## 목차

1. [채택한 결정 요약](#채택한-결정-요약)
2. [섹션 뼈대](#섹션-뼈대)
3. [고민 · 선택지 · 결정](#고민--선택지--결정)
4. [콜아웃](#콜아웃)
5. [흐름도](#흐름도)
6. [상태머신](#상태머신)
7. [계층도](#계층도)
8. [비교 행](#비교-행)
9. [시스템 맵](#시스템-맵)
10. [구성요소 현황표](#구성요소-현황표)
11. [미해결 과제](#미해결-과제)
12. [확인 문제](#확인-문제)
13. [디자인 토큰](#디자인-토큰)

---

## 채택한 결정 요약

마스트헤드 바로 뒤, 목차보다 앞에 놓는다. **채택된 결정만** 넣는다.

```html
<section class="digest">
  <div class="dg-head">
    <h2>채택한 결정</h2>
    <span class="cnt">Adopted · 11</span>
  </div>
  <p class="hint">확정된 것만 모았다. 각 줄을 누르면 검토 과정과 기각된 대안이 있는 본문으로 이동한다.</p>

  <a class="dg-row" href="#s10">
    <span class="dg-id">D-10</span>
    <span class="dg-txt">대본은 <b>오브젝트 스토리지에 파일로</b>, DB에는 링크와 상태만 남긴다.</span>
    <span class="dg-tag">저장소</span>
  </a>

  <a class="dg-row" href="#s11">
    <span class="dg-id">D-11</span>
    <span class="dg-txt">큐는 <b>Redis Stream</b>으로 간다.</span>
    <span class="dg-tag">큐</span>
  </a>

  <div class="dg-foot">
    <span class="lb">보류</span>
    <p><b>D-03 결제 모드</b> — 자동 갱신으로 갈지 일회성으로 갈지 결론이 나지 않았다. <a href="#s3">§3</a></p>
  </div>
</section>
```

`.dg-row`는 `<a>`다 — 줄 전체가 해당 섹션으로 가는 링크가 된다. `href`는 반드시 실제 존재하는 `id`를 가리켜야 한다.

`.cnt`의 숫자는 줄 수, 마스트헤드의 `확정` 수와 같아야 한다.

`.dg-foot`은 보류된 결정을 밝히는 자리다. 요약만 읽은 사람이 전부 정해졌다고 오해하는 것을 막는다. 보류가 없으면 통째로 뺀다.

`.dg-txt`에서 굵게 처리하는 것은 **판단의 핵심어**만이다. 문장 전체를 굵게 하면 스캔이 안 된다.

---

## 섹션 뼈대

```html
<section id="s3">
  <div class="eyebrow">03 / 결제</div>
  <h2 class="sec">인앱결제의 두 갈래</h2>
  <p class="lede">이 장에서 무엇을 다루는지 한두 문장.</p>
  <!-- 내용 -->
</section>
```

`eyebrow`의 번호는 목차 번호와 반드시 일치시킨다. `lede`는 생략 가능하다.

---

## 고민 · 선택지 · 결정

한 주제의 기본 3단 구성이다.

```html
<div class="q"><b>고민</b>Kafka와 Redis 둘 다 다뤄봤는데, 이 파이프라인엔 무엇이 맞는가?</div>

<div class="opt">
  <div class="opt-head"><span class="chip c-r">기각</span><h4>Kafka</h4></div>
  <ul>
    <li class="p">파티션 기반 병렬 소비로 처리량이 수평 확장된다.</li>
    <li class="c">그 강점인 병렬 처리량이 <b>초당 몇 건짜리 워크로드에선 쓸 일이 없다.</b></li>
    <li class="c">단일 노드로 띄우면 복제본이 없어 <b>카프카를 쓰는 이유가 사라진다.</b></li>
  </ul>
</div>

<div class="opt">
  <div class="opt-head"><span class="chip c-a">채택</span><h4>Redis Stream</h4></div>
  <ul>
    <li class="p">컨슈머 그룹과 ACK가 있어 <b>작업이 살아남아 재처리된다.</b></li>
    <li class="c">메모리 기반이라 급사 시 디스크 저장 직전 데이터는 날아갈 수 있다.</li>
    <li class="n">이미 Redis를 띄운다면 추가 인프라가 0이다.</li>
  </ul>
</div>

<div class="cue">
  <div class="cue-top">
    <span class="cue-id">D-11</span>
    <span class="cue-arrow">Kafka · Redis List --&gt; Redis Stream</span>
  </div>
  <div class="cue-body">
    <p class="decision">큐는 Redis Stream으로 간다.</p>
    <p class="why"><b>이유</b>판단 기준은 "카프카가 좋은가"가 아니라 <b>"이 MVP에 그 추가 인프라가 값을 하는가"</b>였다. 초당 몇 건을 한 대가 순서대로 처리하는 구조에서 브로커를 더 이고 갈 이득이 없다.</p>
  </div>
</div>
```

**칩** — 상태는 카드가 아니라 칩이 나타낸다:

| 클래스 | 라벨 |
|---|---|
| `chip c-a` | 채택 |
| `chip c-r` | 기각 |
| `chip c-o` | 보류 |
| `chip c-n` | 유지 |

**리스트 마커** — `li class="p"` 장점(+) · `li class="c"` 단점(−) · `li class="n"` 참고(·)

**`cue-arrow`** — 검토한 것들에서 결론으로 가는 화살표. `A · B --&gt; B` 형태로 쓴다. 화살표는 반드시 `--&gt;`로 이스케이프한다. 보류로 끝났다면 `일회성 · 자동갱신 --&gt; 보류`처럼 적는다.

**`p.decision`**은 결정 한 줄, **`p.why`**는 `<b>이유</b>`로 시작해 근거를 잇는다.

---

## 콜아웃

```html
<div class="note"><b>참고</b> 공식 문서와 커뮤니티 제보가 엇갈리는 지점이다.</div>
<div class="warn"><b>주의</b> 헤더를 생략하면 모든 유저의 주문이 응답으로 내려온다.</div>
```

`warn`은 사고로 이어지는 것에만 쓴다. 남발하면 눈에 안 들어온다.

---

## 흐름도

순차 단계에 쓴다. 사용자 대기처럼 흐름이 끊기는 지점은 `pause`를 붙인다.

```html
<div class="flow">
  <div class="step"><span class="n">1</span><div class="v"><b>영상 업로드</b><p>클라이언트가 스토리지로 직접 올린다</p></div></div>
  <div class="step"><span class="n">2</span><div class="v"><b>작업 큐 발행</b><p>Spring이 처리 요청을 넣는다</p></div></div>
  <div class="step pause"><span class="n">3</span><div class="v"><b>대본 검토</b><p>유저 확인 대기 — 여기서 파이프라인이 끊긴다</p></div></div>
  <div class="step"><span class="n">4</span><div class="v"><b>자막 생성</b><p>확정된 대본으로 재개</p></div></div>
</div>
```

---

## 상태머신

```html
<div class="states">
  <span class="st">NEW</span>
  <span class="arw">--&gt;</span>
  <span class="st">대본 생성 중</span>
  <span class="arw">--&gt;</span>
  <span class="st pend">검토 전</span>
  <span class="arw">--&gt;</span>
  <span class="st">자막 생성 중</span>
  <span class="arw">--&gt;</span>
  <span class="st done">완료</span>
</div>
```

`pend`는 사용자 대기(일시정지) 상태, `done`은 종료 상태.

상태값은 촘촘할수록 좋다. 배치가 멈춘 작업을 발견했을 때 **어느 단계를 다시 시켜야 하는지** 알려면 상태가 그만큼 잘게 쪼개져 있어야 한다.

---

## 계층도

방어 계층, 폴백 단계처럼 겹겹이 쌓이는 구조에 쓴다. `.layer` 하나가 한 계층이다.

```html
<div class="layer">
  <div class="n">1</div>
  <div class="txt">
    <h4>빠른 2xx 응답 + 선저장 후처리</h4>
    <p>2xx를 안 주면 플랫폼이 재시도해준다. 받자마자 저장하고 빠르게 2xx를 반환한 뒤 처리는 뒤로 미룬다.</p>
  </div>
</div>

<div class="layer">
  <div class="n">2</div>
  <div class="txt">
    <h4>배치 대조 <span style="font-family:var(--mono);font-size:11px;color:var(--accept);letter-spacing:.1em">★ 가장 견고</span></h4>
    <p>웹훅과 무관하게 주기적으로 실제 상태를 다시 조회해 우리 DB와 맞춘다.</p>
  </div>
</div>

<div class="layer off">
  <div class="n">3</div>
  <div class="txt">
    <h4>시퀀스 탐지 + 멱등성</h4>
    <p>플랫폼이 이벤트 ID를 줘야 가능하다. 우리가 결정할 수 있는 영역이 아니라 보류.</p>
  </div>
</div>
```

`layer off`는 이번 범위에서 제외된 계층 — 흐리게 표시된다.

---

## 비교 행

항목 대 항목을 나란히 볼 때만 쓴다. 선택지 카드로 충분하면 만들지 않는다.

```html
<div class="rows">
  <div class="row">
    <div class="who">expiresAt</div>
    <div class="what">절대 만료 시점. null로 내려온다는 제보가 있다</div>
  </div>
  <div class="row">
    <div class="who">accessGranted</div>
    <div class="what">접근 권한 여부를 플랫폼이 계산해 내려준다</div>
  </div>
</div>
```

---

## 시스템 맵

의사결정 이유는 빼고 구성만 보여주는 섹션. 문서 맨 앞(목차 앞)에 둔다.

```html
<section id="s0" class="sysmap" style="padding-top:44px">
  <div class="eyebrow">00 / 전체 그림</div>
  <h2 class="sec">시스템 구성과 현황</h2>
  <p class="lede">실선은 직접 운영하는 것, 점선은 갖다 쓰는 외부 서비스다.</p>

  <div class="legend">
    <span><i class="own"></i> 직접 운영</span>
    <span><i class="ext"></i> 외부 서비스</span>
    <span><b class="dot d-a"></b> 방식 확정</span>
    <span><b class="dot d-o"></b> 미확정</span>
  </div>

  <div class="diagram">
    <div class="tier">
      <p class="tier-label">Core — 직접 운영</p>
      <div class="nodes">
        <div class="node own">
          <span class="pin a"></span>
          <span class="role">Business</span>
          <span class="name">Spring 서버</span>
          <span class="sub">비즈니스 로직 · 결제 검증 · 큐 발행</span>
          <span class="badge">운영 지점 1</span>
        </div>
      </div>
    </div>

    <div class="link-row">
      <span class="ln">영상 <b>직접 업로드</b></span>
      <span class="ln">완료 인지 <b>?</b></span>
    </div>

    <div class="vsep">↕</div>

    <div class="tier">
      <p class="tier-label">Queue</p>
      <div class="queues">
        <div class="qbox"><span class="role">→ 하행</span><span class="name">작업 큐</span><span class="sub">설명</span></div>
        <div class="qbox"><span class="role">← 상행</span><span class="name">완료 알림 큐</span><span class="sub">설명</span></div>
      </div>
    </div>
  </div>
</section>
```

노드: `node own`(직접 운영, 실선) / `node ext`(외부, 점선)
상태 점: `pin a`(확정) / `pin o`(미정)

미확정 지점은 `?`로 솔직하게 남긴다. 그림에서 물음표가 보이는 게 다음에 할 일을 드러내는 가장 좋은 방법이다.

---

## 구성요소 현황표

```html
<div class="board">
  <div class="bhead"><span></span><span>구성요소</span><span>현재 방식</span><span>상태</span></div>

  <div class="brow">
    <span class="pin2 a"></span>
    <div class="comp">메시지 큐<small>Redis Stream</small></div>
    <div class="pick"><b>양방향</b> — 작업 큐 + 완료 알림 큐</div>
    <span class="stat done">확정</span>
  </div>

  <div class="brow">
    <span class="pin2 o"></span>
    <div class="comp">오브젝트 스토리지<small>Vendor</small></div>
    <div class="pick">egress 비용 때문에 <b>검토 중</b></div>
    <span class="stat open">미정</span>
  </div>
</div>
```

---

## 미해결 과제

```html
<ol class="todo">
  <li><div>
    <span class="tt">웹훅 재시도 정책 확인</span>
    <span class="td"><span class="blk">차단 요인.</span> 공식 문서에서 찾지 못했다.
    가이드 페이지에서 <b>재시도·재전송</b> 키워드를 직접 확인하거나 채널톡으로 문의한다.
    <b>재시도 정책이 배치 주기를 결정하는 입력값</b>이다.</span>
  </div></li>

  <li><div>
    <span class="tt">대본 파일 규격 확정</span>
    <span class="td">맥미니와 Spring이 같은 포맷을 알고 있어야 한다. 시각 표기 단위·화자 구분·인코딩을 정한다.</span>
  </div></li>
</ol>
```

`.tt` 항목 제목, `.td` 설명. `.blk`는 **다른 결정을 막고 있는 항목**에 붙이는 강조 라벨 — 이걸 붙인 항목이 다음에 할 일이다.

`.td`에 **왜 막혀 있는지 + 어떻게 뚫을지**를 적는다. 이게 없으면 목록이 아니라 불안 목록이 된다.

---

## 확인 문제

섹션 `</section>` 직전에 넣는다.

```html
<div class="quiz" data-ans="2" data-exp="대본은 시각·텍스트로 <b>충분히 구조화된 데이터</b>다. 그런데도 스토리지로 간 이유는 생김새가 아니라 <b>접근 패턴</b> 때문이다.">
  <div class="quiz-head">
    <span class="quiz-tag">확인 문제 09</span>
    <span class="quiz-topic">저장소</span>
  </div>
  <div class="quiz-body">
    <p class="quiz-stem">대본을 DB가 아니라 오브젝트 스토리지에 저장하기로 뒤집은 판단 기준은?</p>
    <div class="quiz-opts">
      <button class="qo" data-i="0">대본 데이터가 비정형이기 때문</button>
      <button class="qo" data-i="1">MySQL이 텍스트 저장을 지원하지 않기 때문</button>
      <button class="qo" data-i="2">접근 패턴이 통째로 읽고 쓰기여서 관계형의 이점을 쓰지 않기 때문</button>
      <button class="qo" data-i="3">JPA 매핑이 복잡해지기 때문</button>
    </div>
    <div class="quiz-fb" role="status" aria-live="polite"></div>
  </div>
</div>
```

`data-ans`는 0부터 센다. `data-exp`에 **큰따옴표를 쓰지 않는다** — 속성값이 끊긴다. `<b>`, `<code>`는 써도 된다.

문제 수를 바꿔도 결과 블록의 분모와 진행 막대는 스크립트가 자동으로 맞춘다. 마스트헤드와 푸터의 집계 숫자만 직접 고친다.

---

## 디자인 토큰

```
--paper  #E6E9ED   배경
--card   #F5F6F8   카드 표면
--ink    #13161B   본문
--ink-2  #3F4650   보조 텍스트
--ink-3  #6C737E   메타 텍스트
--rule   #C2C8D1   구분선
--accept #1B45C8   채택 · 정답 · 확정
--open   #8E6410   보류 · 미정
--reject #9E3A32   기각 · 오답
```

색은 **상태를 뜻한다.** 장식으로 쓰지 않는다. 파란 것은 전부 확정되거나 정답인 것이고, 주황은 전부 아직 안 정해진 것이다. 이 규칙이 깨지면 문서를 훑을 때 색으로 상태를 읽을 수 없게 된다.

서체는 IBM Plex Mono(라벨·번호·코드) + IBM Plex Sans KR(본문). 라벨류는 항상 mono에 `letter-spacing`을 넓게 준다.
