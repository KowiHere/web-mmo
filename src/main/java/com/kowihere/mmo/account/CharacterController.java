package com.kowihere.mmo.account;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.kowihere.mmo.loop.SavedCharacter;
import com.kowihere.mmo.world.ClassDef;
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
    record CreateRequest(String name, String classId) {
    }

    record CharacterView(String key, String name, String mapId, int x, int y,
                         String classId, int level) {
        static CharacterView of(SavedCharacter character) {
            return new CharacterView(character.nameKey(), character.name(),
                    character.mapId(), character.x(), character.y(),
                    character.classId(), character.level());
        }
    }

    /** A class as the selection screen needs it: what it is called and how it fights. */
    record ClassView(String id, String name, String description,
                     int strength, int agility, int intellect) {
        static ClassView of(ClassDef def) {
            return new ClassView(def.id(), def.name(), def.description(),
                    def.startingAttributes().strength(),
                    def.startingAttributes().agility(),
                    def.startingAttributes().intellect());
        }
    }

    /**
     * The classes on offer. Served rather than written into the page, so that
     * adding one is a content change and nothing else.
     */
    @GetMapping("/classes")
    Map<String, List<ClassView>> classes() {
        return Map.of("classes", accounts.classes().stream().map(ClassView::of).toList());
    }

    @GetMapping
    Map<String, List<CharacterView>> list(HttpServletRequest request) {
        long accountId = requireAccount(request);
        return Map.of("characters", accounts.charactersOf(accountId).stream().map(CharacterView::of).toList());
    }

    @PostMapping
    CharacterView create(HttpServletRequest request, @RequestBody CreateRequest body) {
        long accountId = requireAccount(request);
        return CharacterView.of(accounts.createCharacter(accountId, body.name(), body.classId()));
    }

    private long requireAccount(HttpServletRequest request) {
        return SessionCookie.read(request)
                .flatMap(accounts::accountFor)
                .orElseThrow(() -> ApiException.unauthorized("Zaloguj się."));
    }
}
