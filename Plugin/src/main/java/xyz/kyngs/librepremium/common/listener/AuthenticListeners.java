package xyz.kyngs.librepremium.common.listener;

import org.jetbrains.annotations.Nullable;
import xyz.kyngs.librepremium.api.PlatformHandle;
import xyz.kyngs.librepremium.api.database.User;
import xyz.kyngs.librepremium.api.event.events.AuthenticatedEvent;
import xyz.kyngs.librepremium.api.event.events.PremiumLoginSwitchEvent;
import xyz.kyngs.librepremium.api.premium.PremiumException;
import xyz.kyngs.librepremium.api.premium.PremiumUser;
import xyz.kyngs.librepremium.common.AuthenticLibrePremium;
import xyz.kyngs.librepremium.common.command.InvalidCommandArgument;
import xyz.kyngs.librepremium.common.event.events.AuthenticAuthenticatedEvent;
import xyz.kyngs.librepremium.common.event.events.AuthenticPremiumLoginSwitchEvent;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.regex.Pattern;

public class AuthenticListeners<Plugin extends AuthenticLibrePremium<P, S>, P, S> {

    @SuppressWarnings("RegExpSimplifiable") //I don't believe you
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9_]*");

    protected final Plugin plugin;
    protected final PlatformHandle<P, S> platformHandle;

    public AuthenticListeners(Plugin plugin) {
        this.plugin = plugin;
        platformHandle = plugin.getPlatformHandle();
    }

    protected void onPostLogin(P player) {
        var uuid = platformHandle.getUUIDForPlayer(player);
        var current = platformHandle.getPlayerName(player);

        if (plugin.fromFloodgate(uuid)) return;

        var user = plugin.getDatabaseProvider().getByUUID(uuid);
        var sessionTime = plugin.getConfiguration().getSessionTimeout();

        user.setUsername(current);

        if (user.autoLoginEnabled() && user.isSameUsername()) {
            plugin.getPlatformHandle().getAudienceForPlayer(player).sendMessage(plugin.getMessages().getMessage("info-premium-logged-in"));
            plugin.getEventProvider().fire(AuthenticatedEvent.class, new AuthenticAuthenticatedEvent<>(user, player, plugin, AuthenticatedEvent.AuthenticationReason.PREMIUM));
        } else if (sessionTime != null && user.getLastAuthentication() != null && platformHandle.getIP(player).equals(user.getIp()) && user.getLastAuthentication().toLocalDateTime().plus(sessionTime).isAfter(LocalDateTime.now())) {
            plugin.getPlatformHandle().getAudienceForPlayer(player).sendMessage(plugin.getMessages().getMessage("info-session-logged-in"));
            plugin.getEventProvider().fire(AuthenticatedEvent.class, new AuthenticAuthenticatedEvent<>(user, player, plugin, AuthenticatedEvent.AuthenticationReason.SESSION));
        } else {
            if (user.autoLoginEnabled()) {
                plugin.getPlatformHandle().getAudienceForPlayer(player).sendMessage(plugin.getMessages().getMessage("username-issue-detected"));
                user.setPremiumUUID(null);
                plugin.getDatabaseProvider().updateUser(user);
            }
            plugin.getAuthorizationProvider().startTracking(user, player);
        }

        user.setLastSeen(Timestamp.valueOf(LocalDateTime.now()));

        plugin.getDatabaseProvider().updateUser(user);

    }

    protected void onPlayerDisconnect(P player) {
        plugin.onExit(player);
        plugin.getAuthorizationProvider().onExit(player);
    }

    protected PreLoginResult onPreLogin(String username) {
        if (username.length() > 16 || !NAME_PATTERN.matcher(username).matches()) {
            return new PreLoginResult(PreLoginState.DENIED, plugin.getMessages().getMessage("kick-illegal-username"));
        }

        PremiumUser premium;

        try {
            premium = plugin.getPremiumProvider().getUserForName(username);
        } catch (PremiumException e) {
            var message = switch (e.getIssue()) {
                case THROTTLED -> plugin.getMessages().getMessage("kick-premium-error-throttled");
                default -> {
                    plugin.getLogger().error("Encountered an exception while communicating with the Mojang API!");
                    e.printStackTrace();
                    yield plugin.getMessages().getMessage("kick-premium-error-undefined");
                }
            };

            return new PreLoginResult(PreLoginState.DENIED, message);
        }

        if (premium == null) {
            User user;
            try {
                user = checkAndValidateByName(username, null, true);
            } catch (InvalidCommandArgument e) {
                return new PreLoginResult(PreLoginState.DENIED, e.getUserFuckUp());
            }

            //noinspection ConstantConditions //kyngs: There's no way IntelliJ is right
            if (user.getPremiumUUID() != null) {
                user.setPremiumUUID(null);
                plugin.getDatabaseProvider().updateUser(user);
                plugin.getEventProvider().fire(PremiumLoginSwitchEvent.class, new AuthenticPremiumLoginSwitchEvent<>(user, null, plugin));
            }
        } else {
            var premiumID = premium.uuid();
            var user = plugin.getDatabaseProvider().getByPremiumUUID(premiumID);

            if (user == null) {
                User userByName;
                try {
                    userByName = checkAndValidateByName(username, premiumID, true);
                } catch (InvalidCommandArgument e) {
                    return new PreLoginResult(PreLoginState.DENIED, e.getUserFuckUp());
                }

                //noinspection ConstantConditions //kyngs: There's no way IntelliJ is right
                if (userByName.autoLoginEnabled()) return new PreLoginResult(PreLoginState.FORCE_ONLINE, null);
            } else {
                User byName;
                try {
                    byName = checkAndValidateByName(username, premiumID, false);
                } catch (InvalidCommandArgument e) {
                    return new PreLoginResult(PreLoginState.DENIED, e.getUserFuckUp());
                }

                if (byName != null && !user.equals(byName)) {
                    // Oh, no
                    return new PreLoginResult(PreLoginState.DENIED, plugin.getMessages().getMessage("kick-name-mismatch",
                            "%nickname%", username
                    ));
                }

                if (!user.getLastNickname().contentEquals(premium.name())) {
                    user.setLastNickname(premium.name());

                    plugin.getDatabaseProvider().updateUser(user);
                }

                return new PreLoginResult(PreLoginState.FORCE_ONLINE, null);
            }
        }

        return new PreLoginResult(PreLoginState.FORCE_OFFLINE, null);
    }

    private User checkAndValidateByName(String username, @Nullable UUID premiumID, boolean generate) throws InvalidCommandArgument {
        var user = plugin.getDatabaseProvider().getByName(username);

        if (user != null) {
            if (!user.getLastNickname().contentEquals(username)) {
                throw new InvalidCommandArgument(plugin.getMessages().getMessage("kick-invalid-case-username",
                        "%username%", user.getLastNickname()
                ));
            }
        } else if (generate) {
            var newID = plugin.generateNewUUID(
                    username,
                    premiumID
            );

            var conflictingUser = plugin.getDatabaseProvider().getByUUID(newID);

            if (conflictingUser != null) {
                throw new InvalidCommandArgument(plugin.getMessages().getMessage("kick-occupied-username",
                        "%username%", conflictingUser.getLastNickname()
                ));
            }

            if (premiumID != null && plugin.getConfiguration().autoRegister()) {
                user = new User(
                        newID,
                        premiumID,
                        null,
                        username,
                        Timestamp.valueOf(LocalDateTime.now()),
                        Timestamp.valueOf(LocalDateTime.now()),
                        null,
                        null,
                        null,
                        null);
            } else {
                user = new User(
                        newID,
                        null,
                        null,
                        username,
                        Timestamp.valueOf(LocalDateTime.now()),
                        Timestamp.valueOf(LocalDateTime.now()),
                        null,
                        null,
                        null,
                        null);
            }

            plugin.getDatabaseProvider().insertUser(user);
        } else return null;

        return user;
    }

    protected S chooseServer(P player) {
        var id = platformHandle.getUUIDForPlayer(player);
        var fromFloodgate = plugin.fromFloodgate(id);

        var sessionTime = plugin.getConfiguration().getSessionTimeout();

        var user = fromFloodgate ? null : plugin.getDatabaseProvider().getByUUID(id);

        if (fromFloodgate || user.autoLoginEnabled() || (sessionTime != null && user.getLastAuthentication() != null && platformHandle.getIP(player).equals(user.getIp()) && user.getLastAuthentication().toLocalDateTime().plus(sessionTime).isAfter(LocalDateTime.now()))) {
            return plugin.chooseLobby(user, player, true);
        } else {
            return plugin.chooseLimbo(user, player);
        }
    }
}
