package com.example.chatgateway.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.chatgateway.exception.GatewayException;
import org.junit.jupiter.api.Test;

class LocalAuthorityStoreTest {
  LocalAuthorityStore store = new LocalAuthorityStore();

  Session guest() {
    return new Session("hash", "guest", "GUEST", "channel", "ACTIVE", 0, 300000, null);
  }

  @Test
  void guestIdsAreIssuedUniquelyOnCreate() {
    var first =
        store.create(
            new Session("first", null, "GUEST", "channel", "ACTIVE", 0, 300000, null), "create-1");
    var second =
        store.create(
            new Session("second", null, "GUEST", "channel", "ACTIVE", 0, 300000, null), "create-2");
    assertThat(first.subject()).matches("G[0-9]{12}");
    assertThat(second.subject()).matches("G[0-9]{12}").isNotEqualTo(first.subject());
  }

  @Test
  void replacementCannotLinkAnotherGuest() {
    store.create(guest(), null);
    var member =
        new Session("new", "member", "MEMBER", "channel", "ACTIVE", 200, 300200, "other-guest");
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
        new Session("new", "member", "MEMBER", "channel", "ACTIVE", 300000, 600000, "guest");
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
                            id, "member", "MEMBER", "channel", "ACTIVE", 200, 300200, "guest"),
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
        new Session("new-hash", "member", "MEMBER", "channel", "ACTIVE", 200, 300200, "guest");
    store.replaceGuest("hash", "key", member, 200);
    assertThat(store.find("hash").state()).isEqualTo("REVOKED");
    assertThat(store.find("new-hash")).isEqualTo(member);
    assertThatThrownBy(() -> store.touch("hash", 201, 300000, null)).hasMessage("UNAUTHENTICATED");
  }
}
