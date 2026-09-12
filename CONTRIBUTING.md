# 개발 작업 작성 규칙

현재 설계 기준은 [명세 목차](docs/knowledge-link/00-overview.md)다. 이슈에는 작업 목표, PR에는 최종 변경과 검증 결과, 커밋에는 한 변경의 의도를 기록한다.

## 이슈와 PR

- 기능 요청: 해결할 문제 → 제안 동작 → 완료 조건.
- 버그 신고: 재현 방법 → 기대·실제 동작 → 환경.
- 개발 작업: 문서·테스트·설정·리팩터링의 목적과 완료 조건.
- PR: 변경 이유와 결과 → 관련 이슈·명세 → 실제 검증 → 필요한 검토 사항.

F01·T01 같은 명세 식별자와 Jira 키는 해당할 때만 적는다. 작은 PR은 짧게 작성하고 해당하지 않는 절은 삭제한다. 검증을 실행하지 않았다면 이유를 적는다. 기본 라벨·담당자는 저장소 설정에 의존하므로 템플릿에서 자동 지정하지 않는다.

GitHub 이슈를 완료하는 PR에는 `Closes #123`, 참고만 할 때는 `Refs #123`을 사용한다. Jira 키는 `PAY-123`처럼 별도 기록하며 GitHub 이슈 자동 종료와 구분한다.

템플릿은 `.github/pull_request_template.md`와 `.github/ISSUE_TEMPLATE/`에 있다. GitHub에서 사용하려면 파일을 [KnowledgeLink 저장소](https://github.com/calmkeep22/KnowledgeLink)의 기본 브랜치에 반영한다. 로컬 파일 생성만으로 원격 화면에 표시되지는 않는다.

## 커밋 형식

Conventional Commits 형식을 사용하며 제목·본문은 한국어로 작성해도 된다.

```text
feat(auth): 초기 비밀번호 변경 API 추가

초기 비밀번호를 바꾸기 전에는 업무 API에 접근하지 못하게 한다.

Refs: #12
```

| type | 용도 |
| --- | --- |
| feat / fix | 기능 추가 / 버그 수정 |
| docs / test | 문서 / 테스트 |
| refactor / perf | 동작 유지 구조 개선 / 성능 개선 |
| build / ci | 빌드·의존성 / CI 설정 |
| style / chore / revert | 서식 / 기타 관리 / 변경 되돌리기 |

scope는 선택이며 `auth`, `scope`, `sync`, `search`, `ai`, `ui`처럼 변경 대상을 쓴다. 호환성을 깨는 변경은 `feat(api)!: ...`와 `BREAKING CHANGE: ...` footer로 설명한다. PR 제목도 같은 형식이면 squash merge 커밋 작성에 활용할 수 있다.

새로 clone한 작업 폴더에서는 다음 설정을 한 번 적용한다. 다른 저장소에는 영향을 주지 않는다.

```bash
git config --local commit.template .gitmessage
git config --local core.commentChar '#'
git config --local commit.cleanup strip
git config --local --get commit.template
git commit
```

`git commit`으로 편집기를 열면 안내가 표시되고 `#` 안내 줄은 커밋 메시지에서 제거된다. `git commit -m`은 템플릿을 사용하지 않는다. GUI·IDE 편집기의 지원 여부도 다를 수 있다. 이 설정은 양식 안내이며 형식을 강제하는 훅은 아니다. Git 로컬 설정은 clone할 때 복제되지 않는다.

## 참고한 자료

2026-09-12에 공식 문서와 Vite 공개 템플릿을 확인하고 이 프로젝트에 맞게 한국어 양식을 작성했다. Vite의 문제 설명·재현·관련 이슈·검증 중심 구성을 참고했으며 해당 프로젝트의 기여 정책이나 자동 종료 규칙은 가져오지 않았다.

- [GitHub Issue Forms 문법](https://docs.github.com/en/communities/using-templates-to-encourage-useful-issues-and-pull-requests/syntax-for-issue-forms)
- [GitHub PR 템플릿 설정](https://docs.github.com/en/communities/using-templates-to-encourage-useful-issues-and-pull-requests/creating-a-pull-request-template-for-your-repository)
- [Vite PR 템플릿](https://github.com/vitejs/vite/blob/main/.github/PULL_REQUEST_TEMPLATE.md)
- [Vite 버그 양식](https://github.com/vitejs/vite/blob/main/.github/ISSUE_TEMPLATE/bug_report.yml)
- [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/)
- [Git commit.template 설정](https://git-scm.com/book/en/v2/Customizing-Git-Git-Configuration)
