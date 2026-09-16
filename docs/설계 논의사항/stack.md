# 스택 선택

- 날짜: 2026-09-17
- 상태: 결정 (DB·영속성 세부는 사용자 확인 전)

## 논제

언어와 프레임워크 버전, DB, 빌드 도구를 무엇으로 할 것인가. 특히 Java 21과 25, Spring Boot 3.5.x와 4.1.x 중 무엇을 고를 것인가.

## 결론

| 항목 | 선택 |
|---|---|
| 언어 | Java |
| JDK | Java 25 (LTS) |
| 프레임워크 | Spring Boot 4.1.x |
| 빌드 | Gradle Kotlin DSL |
| DB | H2 파일 모드 (Claude 제안, 확인 전) |
| 영속성 | Spring Data JPA + Flyway (Claude 제안, 확인 전) |

## 근거

1. Spring Boot 3.5는 2026-06-30에 오픈소스 지원이 끝났다. 새로 시작하는 프로젝트를 지원 종료된 브랜치에 올릴 이유가 없다. 4.1.0이 2026-06-10에 나온 현재 라인이다.
2. Java 25는 LTS이고 Boot 4.x가 일급으로 지원한다(공식 시스템 요구사항 기준 Java 17~26 테스트). 기존 런타임 제약이 없으니 최신 LTS를 피할 이유가 없다.
3. JEP 491(JDK 24에 포함)로 `synchronized`·`Object.wait()`에서의 캐리어 핀닝이 사라졌다. 25는 그것을 품은 첫 LTS다. 다만 우리 검색 경로는 park 기반 대기 하나와 메모리 스냅샷 조회뿐이라, 이 수정이 지금 구조의 병목을 바꾸지는 않는다. 네이티브 프레임에서의 핀닝은 남는다.
4. Spring Framework 7이 `@Retryable`·`RetryTemplate`·`@ConcurrencyLimit`을 코어에 넣었다. 범위 밖으로 미룬 재시도와, 7번에서 정한 공급사별 동시 호출 상한을 외부 의존성 없이 붙일 자리가 된다. 문서도 가상 스레드에서 스레드 풀 상한이 없기 때문에 이런 제한이 필요하다고 짚는다.
5. Boot 4의 파열음(Jackson 3로의 패키지 이동, Security 7 기본값, JSpecify)은 기존 코드를 옮길 때의 비용이다. 새로 시작하면 대부분 해당되지 않는다. 사용자가 따로 조사한 바로도 3.5.x의 사용법이 4.1.x에서 대부분 유지되며, starter 이름, Jackson import 경로(`tools.jackson`), Dockerfile의 `jarmode=tools` 정도만 주의하면 된다.
6. 남는 실질 비용은 인터넷 예제가 아직 3.x·Jackson 2 기준이라는 점이다.

## 확인할 버전 (프로젝트 골격 만들 때)

| 항목 | 필요 버전 |
|---|---|
| Gradle | Java 25 툴체인은 9.1+ (Boot 4.1은 8.14+ 또는 9.x 요구) |
| Lombok (쓴다면) | 1.18.40+ |
| Mockito / ByteBuddy | 5.16+ / 1.17.5+ |
| springdoc-openapi | 3.x (Boot 4 대응) |

## 논의 흐름

1. Claude가 Java 21 + Boot 3.5.x를 안전한 선택으로 제안했다.
2. 사용자가 Java 25도 LTS이고 가상 스레드 핀닝 문제가 해결됐는데 아직 미성숙하냐고 물었다. Claude가 핀닝 수정은 JDK 24에 들어갔고 25가 그것을 품은 첫 LTS이며, 우리 경로에서는 효과가 크지 않다고 답했다.
3. 사용자가 Boot 4.1.x와 3.5.x를 조사해 비교해 달라고 요청했다. 조사 결과 3.5의 오픈소스 지원 종료가 드러나 사실상 선택지가 하나로 좁혀졌다.
4. 사용자가 4.1.x 사용법이 3.5.x와 대부분 같다는 조사 결과를 보태고, 언어는 Java로 정했다.

## 참고

- Spring Boot 시스템 요구사항: https://docs.spring.io/spring-boot/system-requirements.html
- Spring Boot 버전·지원 종료일 정리: https://www.herodevs.com/blog-posts/spring-boot-versions-eol-dates-and-latest-releases-april-2026
- Framework 7 회복탄력성 기능: https://docs.spring.io/spring-framework/reference/core/resilience.html
- Jackson 3 지원: https://spring.io/blog/2025/10/07/introducing-jackson-3-support-in-spring/
- Boot 4.0 마이그레이션 가이드: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide
