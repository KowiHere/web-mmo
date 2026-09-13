package com.kowihere.mmo.account;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.kowihere.mmo.loop.SavedCharacter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/characters")
class CharacterController {

    private final AccountService accounts;

    CharacterController(AccountService accounts) {
        this.accounts = accounts;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CreateRequest(String name) {
    }

    record CharacterView(String key, String name, String mapId, int x, int y) {
        static CharacterView of(SavedCharacter character) {
            return new CharacterView(character.nameKey(), character.name(),
                    character.mapId(), character.x(), character.y());
        }
    }

    @GetMapping
    Map<String, List<CharacterView>> list(HttpServletRequest request) {
        long accountId = requireAccount(request);
        return Map.of("characters", accounts.charactersOf(accountId).stream().map(CharacterView::of).toList());
    }

    @PostMapping
    CharacterView create(HttpServletRequest request, @RequestBody CreateRequest body) {
        long accountId = requireAccount(request);
        return CharacterView.of(accounts.createCharacter(accountId, body.name()));
    }

    private long requireAccount(HttpServletRequest request) {
        return SessionCookie.read(request)
                .flatMap(accounts::accountFor)
                .orElseThrow(() -> ApiException.unauthorized("Zaloguj się."));
    }
}
