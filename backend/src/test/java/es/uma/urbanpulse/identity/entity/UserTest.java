package es.uma.urbanpulse.identity.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #373: a user created by the application (sign-up, an administrator) starts with the same channels
 * as the database default, email on and push off, because Hibernate writes every column and would
 * otherwise skip that default (plan of #373, section 9).
 */
@DisplayName("User")
class UserTest {

    @Test
    @DisplayName("a new user has email on and push off")
    void aNewUserHasEmailOnAndPushOff() {
        // Act
        var user = new User();

        // Assert
        assertThat(user.isNotifyByEmail()).isTrue();
        assertThat(user.isNotifyByPush()).isFalse();
    }
}
