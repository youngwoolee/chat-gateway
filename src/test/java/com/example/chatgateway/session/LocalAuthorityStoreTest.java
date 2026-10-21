package com.example.chatgateway.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.chatgateway.exception.GatewayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LocalAuthorityStoreTest {
  LocalAuthorityStore store = new LocalAuthorityStore();

  @BeforeEach
  void seedMember() {
    store.create(
        new Session(
            "member-hash", "member", "MEMBER", "channel", "ACTIVE", 0, 300000, null, "thread"),
        null);
  }

  Session guest() {
    return new Session("hash", "guest", "GUEST", "channel", "ACTIVE", 0, 300000, null, "thread");
  }

  @Test
  void replacementCannotLinkAnotherGuest() {
    store.create(guest(), null);
    var member =
        new Session(
            "new", "member", "MEMBER", "channel", "ACTIVE", 200, 300200, "other-guest", "thread");
    assertThatThrownBy(() -> store.replaceGuest("hash", "key", member, 200)).hasMessage("CONFLICT");
    assertThat(store.find("hash").state()).isEqualTo("ACTIVE");
    assertThat(store.find("new")).isNull();
  }

  @Test
  void expirationIsExactAndCannotBeRevived() {
    store.create(guest(), null);
    assertThatThrownBy(() -> store.touch("hash", 300000, 300000, null))
        .isInstanceOf(GatewayException.class)
        .hasMessage("UNAUTHENTICATED");
    assertThat(store.find("hash").expiresAt()).isEqualTo(300000);
  }

  @Test
  void sameActivityDoesNotExtendTwice() {
    store.create(guest(), null);
    assertThat(store.touch("hash", 100, 300000, "key").expiresAt()).isEqualTo(300100);
    assertThat(store.touch("hash", 200, 300000, "key").expiresAt()).isEqualTo(300100);
  }

  @Test
  void expiredGuestCannotBeReplaced() {
    store.create(guest(), null);
    var member =
        new Session(
            "new", "member", "MEMBER", "channel", "ACTIVE", 300000, 600000, "guest", "thread");
    assertThatThrownBy(() -> store.replaceGuest("hash", "key", member, 300000))
        .hasMessage("UNAUTHENTICATED");
    assertThat(store.find("new")).isNull();
  }

  @Test
  void concurrentReplacementHasOnlyOneWinner() throws Exception {
    store.create(guest(), null);
    var start = new java.util.concurrent.CountDownLatch(1);
    var wins = new java.util.concurrent.atomic.AtomicInteger();
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
      var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 8; i++) {
        String id = "new-" + i;
        tasks.add(
            executor.submit(
                () -> {
                  start.await();
                  try {
                    store.replaceGuest(
                        "hash",
                        id,
                        new Session(
                            id, "member", "MEMBER", "channel", "ACTIVE", 200, 300200, "guest",
                            "thread"),
                        200);
                    wins.incrementAndGet();
                  } catch (GatewayException e) {
                    assertThat(e.status()).isEqualTo("UNAUTHENTICATED");
                  }
                  return null;
                }));
      }
      start.countDown();
      for (var task : tasks) task.get();
    }
    assertThat(wins.get()).isEqualTo(1);
  }

  @Test
  void promotionAtomicallyRevokesGuest() {
    store.create(guest(), null);
    var member =
        new Session(
            "new-hash", "member", "MEMBER", "channel", "ACTIVE", 200, 300200, "guest", "thread");
    store.replaceGuest("hash", "key", member, 200);
    assertThat(store.find("hash").state()).isEqualTo("REVOKED");
    assertThat(store.find("new-hash")).isEqualTo(member);
    assertThatThrownBy(() -> store.touch("hash", 201, 300000, null)).hasMessage("UNAUTHENTICATED");
  }

  @Test
  void concurrentRestartsOnlyCreateOneSuccessorAndRetriesCannotReviveIt() throws Exception {
    try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Boolean>>();
      for (int i = 0; i < 8; i++) {
        int n = i;
        tasks.add(
            () -> {
              try {
                store.restart(
                    new AuthorityStore.RestartSession(
                        "member-hash", "key-" + n, "thread-" + n, "new-" + n, 100, 300000));
                return true;
              } catch (GatewayException denied) {
                return false;
              }
            });
      }
      int winners = 0;
      for (var result : pool.invokeAll(tasks)) if (result.get()) winners++;
      assertThat(winners).isEqualTo(1);
    }
    store.create(guest(), null);
    var command =
        new AuthorityStore.RestartSession("hash", "retry", "new-thread", "new-hash", 100, 300000);
    var created = store.restart(command);
    assertThat(store.restart(command)).isEqualTo(created);
    store.revoke(created.idHash());
    assertThatThrownBy(() -> store.restart(command)).hasMessage("UNAUTHENTICATED");
  }
}
