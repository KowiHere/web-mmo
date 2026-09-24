package com.kowihere.mmo.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads NPC definitions from classpath JSON at startup - the fifth twin of
 * {@link ItemDefLoader}.
 *
 * <p>It validates harder than the others, because a broken conversation fails
 * in a way nothing else in the game reports. A creature with the wrong health
 * is visibly wrong the first time somebody fights it; a dialogue option
 * pointing at a node that does not exist is wrong only for the player who
 * happens to pick that option, and looks to them like the game freezing.
 */
@Component
public class NpcDefLoader {

    private static final String LOCATION = "classpath:npcs/*.json";

    /**
     * Which deed in a conversation belongs to which declared function.
     *
     * <p>Every function past talking is reached the same way: an option in a
     * conversation does it. That is why this is a map and not a chain of ifs -
     * a new function is an entry here and the pair of checks below covers it
     * without a line of new logic.
     */
    private static final Map<DialogueAction, NpcFunction> DEEDS = Map.of(
            DialogueAction.HEAL, NpcFunction.HEALER,
            DialogueAction.OPEN_SHOP, NpcFunction.SHOP,
            DialogueAction.RESET_SKILLS, NpcFunction.MASTER);

    private final ObjectMapper mapper = new ObjectMapper();
    private final String location;
    private final Map<String, ItemDef> items;
    private final Map<String, CurrencyDef> currencies;
    private final Map<String, ClassDef> classes;

    public NpcDefLoader() {
        this(LOCATION);
    }

    /** Where the definitions live. Content need not sit at the default path. */
    public NpcDefLoader(String location) {
        this(location, new ItemDefLoader().loadAll(), new CurrencyDefLoader().loadAll());
    }

    /**
     * @param items      what a trader is allowed to stock, checked while loading
     * @param currencies the money that exists, likewise. A stall priced in a
     *                   currency nobody can hold is a stall nobody can buy from,
     *                   and nothing at runtime would ever say so.
     */
    public NpcDefLoader(String location, Map<String, ItemDef> items,
                        Map<String, CurrencyDef> currencies) {
        this(location, items, currencies, new ClassDefLoader().loadAll());
    }

    public NpcDefLoader(String location, Map<String, ItemDef> items,
                        Map<String, CurrencyDef> currencies, Map<String, ClassDef> classes) {
        this.location = location;
        this.items = items;
        this.currencies = currencies;
        this.classes = classes;
    }

    public Map<String, NpcDef> loadAll() {
        var resolver = new PathMatchingResourcePatternResolver();
        var loaded = new LinkedHashMap<String, NpcDef>();
        try {
            for (Resource resource : resolver.getResources(location)) {
                NpcDef def = parse(resource);
                if (loaded.putIfAbsent(def.id(), def) != null) {
                    throw new IllegalStateException("Duplicate NPC id: " + def.id());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not read NPC definitions from " + location, e);
        }
        return Map.copyOf(loaded);
    }

    private NpcDef parse(Resource resource) throws IOException {
        JsonNode root;
        try (InputStream in = resource.getInputStream()) {
            root = mapper.readTree(in);
        }
        String where = resource.getFilename();

        String id = text(root, "id", where);
        String name = root.path("name").asText(id);

        String rawKind = text(root, "kind", where);
        NpcKind kind = NpcKind.parse(rawKind);
        if (kind == null) {
            throw new IllegalStateException(where + ": unknown kind '" + rawKind
                    + "'. Known kinds: PERSON, OBJECT.");
        }

        Set<NpcFunction> functions = functions(root, where, id);
        Dialogue dialogue = root.has("dialogue") ? dialogue(root.get("dialogue"), where, id) : null;

        // Both ways round. A talker with no conversation opens an empty window;
        // a conversation on somebody who does not talk is content nobody will
        // ever see, and in both cases the JSON says one thing and means another.
        if (functions.contains(NpcFunction.DIALOGUE) && dialogue == null) {
            throw new IllegalStateException(where + ": '" + id
                    + "' has the DIALOGUE function but no \"dialogue\" to say.");
        }
        if (dialogue != null && !functions.contains(NpcFunction.DIALOGUE)) {
            throw new IllegalStateException(where + ": '" + id
                    + "' has a \"dialogue\" but does not list DIALOGUE among its functions,"
                    + " so nobody could ever start it.");
        }
        checkDeedsMatchFunctions(functions, dialogue, where, id);
        Shop shop = root.has("shop") ? shop(root.get("shop"), where, id) : null;
        if (functions.contains(NpcFunction.SHOP) && shop == null) {
            throw new IllegalStateException(where + ": '" + id
                    + "' has the SHOP function but no \"shop\" to sell from.");
        }
        if (shop != null && !functions.contains(NpcFunction.SHOP)) {
            throw new IllegalStateException(where + ": '" + id
                    + "' has a \"shop\" but does not list SHOP among its functions.");
        }
        Master master = root.has("master") ? master(root.get("master"), where, id) : null;
        if (functions.contains(NpcFunction.MASTER) && master == null) {
            throw new IllegalStateException(where + ": '" + id
                    + "' is a MASTER but does not say whose class it keeps.");
        }
        if (master != null && !functions.contains(NpcFunction.MASTER)) {
            throw new IllegalStateException(where + ": '" + id
                    + "' has a \"master\" but does not list MASTER among its functions.");
        }
        return new NpcDef(id, name, kind, functions, dialogue, shop, master);
    }

    /**
     * The same pair of questions as DIALOGUE against its tree, asked of every
     * deed a conversation can do. A function listed and never offered is a
     * promise the NPC does not keep; a deed offered by somebody who does not
     * list the function is a list of functions that lies about them.
     */
    private static void checkDeedsMatchFunctions(Set<NpcFunction> functions, Dialogue dialogue,
                                                 String where, String id) {
        Set<DialogueAction> offered = EnumSet.noneOf(DialogueAction.class);
        if (dialogue != null) {
            for (DialogueNode node : dialogue.nodes().values()) {
                for (DialogueOption option : node.options()) {
                    if (option.deed() != null) {
                        offered.add(option.deed());
                    }
                }
            }
        }
        for (Map.Entry<DialogueAction, NpcFunction> pair : DEEDS.entrySet()) {
            boolean does = functions.contains(pair.getValue());
            boolean says = offered.contains(pair.getKey());
            if (does && !says) {
                throw new IllegalStateException(where + ": '" + id + "' is a " + pair.getValue()
                        + " but no option anywhere does " + pair.getKey()
                        + ", so nobody could ever get it to.");
            }
            if (says && !does) {
                throw new IllegalStateException(where + ": '" + id + "' offers " + pair.getKey()
                        + " but does not list " + pair.getValue() + " among its functions.");
            }
        }
    }

    private Shop shop(JsonNode root, String where, String id) {
        String currencyId = text(root, "currency", where);
        if (!currencies.containsKey(currencyId)) {
            throw new IllegalStateException(where + ": '" + id + "' deals in '" + currencyId
                    + "', which is not a currency. Known: " + currencies.keySet());
        }
        List<String> sells = new ArrayList<>();
        for (JsonNode entry : root.path("sells")) {
            String itemId = entry.asText();
            if (!items.containsKey(itemId)) {
                throw new IllegalStateException(where + ": '" + id + "' stocks '" + itemId
                        + "', which is not an item.");
            }
            if (sells.contains(itemId)) {
                throw new IllegalStateException(where + ": '" + id + "' stocks '" + itemId
                        + "' twice, so it would appear on the shelf twice.");
            }
            sells.add(itemId);
        }
        if (sells.isEmpty()) {
            // An empty stall opens, shows nothing, and buys nothing back - since
            // a trader only buys what they sell. It is a shop in name only.
            throw new IllegalStateException(where + ": '" + id + "' has a shop with nothing in it.");
        }
        return new Shop(currencyId, sells);
    }

    private Master master(JsonNode root, String where, String id) {
        String classId = text(root, "classId", where);
        if (!classes.containsKey(classId)) {
            // A master of a class that does not exist keeps nobody: every
            // character who walked up would be told they are the wrong class,
            // and nothing at runtime would ever say why.
            throw new IllegalStateException(where + ": '" + id + "' keeps the class '" + classId
                    + "', which does not exist. Known: " + classes.keySet());
        }
        return new Master(classId);
    }

    private static Set<NpcFunction> functions(JsonNode root, String where, String id) {
        JsonNode list = root.get("functions");
        if (list == null || !list.isArray() || list.isEmpty()) {
            throw new IllegalStateException(where + ": '" + id
                    + "' has no functions, so there would be nothing to do with it.");
        }
        Set<NpcFunction> functions = EnumSet.noneOf(NpcFunction.class);
        for (JsonNode entry : list) {
            String raw = entry.asText();
            NpcFunction function = NpcFunction.parse(raw);
            if (function == null) {
                throw new IllegalStateException(where + ": '" + id
                        + "' names an unknown function '" + raw + "'.");
            }
            if (!function.isImplemented()) {
                throw new IllegalStateException(where + ": '" + id + "' wants " + function
                        + ", which nothing can honour yet - " + function.whyNotYet() + ".");
            }
            functions.add(function);
        }
        return functions;
    }

    private static Dialogue dialogue(JsonNode root, String where, String id) {
        JsonNode nodes = root.get("nodes");
        if (nodes == null || !nodes.isObject() || nodes.isEmpty()) {
            throw new IllegalStateException(where + ": '" + id + "' has a dialogue with no nodes.");
        }
        String startId = root.path("start").asText(null);
        if (startId == null || startId.isBlank()) {
            throw new IllegalStateException(where + ": '" + id
                    + "' has a dialogue that does not say which node it starts on.");
        }

        Map<String, DialogueNode> parsed = new LinkedHashMap<>();
        for (Iterator<String> it = nodes.fieldNames(); it.hasNext(); ) {
            String nodeId = it.next();
            parsed.put(nodeId, node(nodeId, nodes.get(nodeId), where, id));
        }
        if (!parsed.containsKey(startId)) {
            throw new IllegalStateException(where + ": '" + id + "' starts on node '" + startId
                    + "', which does not exist.");
        }
        checkEveryGotoLeadsSomewhere(parsed, where, id);
        checkEveryNodeIsReachable(parsed, startId, where, id);
        return new Dialogue(startId, parsed);
    }

    private static DialogueNode node(String nodeId, JsonNode node, String where, String id) {
        String said = node.path("text").asText(null);
        if (said == null || said.isBlank()) {
            throw new IllegalStateException(
                    where + ": '" + id + "' node '" + nodeId + "' has nothing to say.");
        }
        JsonNode options = node.get("options");
        if (options == null || !options.isArray() || options.isEmpty()) {
            // Not pedantry: a node with no options is a window with no button
            // on it. The player would have to reload the page to get out.
            throw new IllegalStateException(where + ": '" + id + "' node '" + nodeId
                    + "' has no options, so anybody reaching it would be stuck there.");
        }
        List<DialogueOption> choices = new ArrayList<>();
        for (JsonNode option : options) {
            choices.add(option(option, nodeId, where, id));
        }
        return new DialogueNode(nodeId, said, choices);
    }

    private static DialogueOption option(JsonNode option, String nodeId, String where, String id) {
        String said = option.path("text").asText(null);
        if (said == null || said.isBlank()) {
            throw new IllegalStateException(
                    where + ": '" + id + "' node '" + nodeId + "' has an option with no text.");
        }
        String goTo = option.path("goto").asText(null);
        String rawAction = option.path("action").asText(null);
        DialogueAction action = null;
        if (rawAction != null && !rawAction.isBlank()) {
            action = DialogueAction.parse(rawAction);
            if (action == null) {
                throw new IllegalStateException(where + ": '" + id + "' node '" + nodeId
                        + "' has an option with an unknown action '" + rawAction + "'.");
            }
        }
        boolean leads = goTo != null && !goTo.isBlank();
        // One rule, in both directions: every option has to say where the talk
        // goes. END is the one action that is itself somewhere to go; anything
        // else happens and the conversation carries on, so it still needs a
        // "goto" - without one it would heal and leave the window on nothing.
        if (!leads && action == null) {
            throw new IllegalStateException(where + ": '" + id + "' node '" + nodeId
                    + "' has the option \"" + said + "\" with neither \"goto\" nor \"action\";"
                    + " it has to lead somewhere or end.");
        }
        if (!leads && !action.isDestination()) {
            throw new IllegalStateException(where + ": '" + id + "' node '" + nodeId
                    + "' has the option \"" + said + "\" doing " + action
                    + " and then going nowhere; give it a \"goto\".");
        }
        if (leads && action != null && action.isDestination()) {
            throw new IllegalStateException(where + ": '" + id + "' node '" + nodeId
                    + "' has the option \"" + said + "\" both ending the talk and leading to '"
                    + goTo + "'; " + action + " goes nowhere.");
        }
        return new DialogueOption(said, leads ? goTo : null, action);
    }

    private static void checkEveryGotoLeadsSomewhere(
            Map<String, DialogueNode> nodes, String where, String id) {
        for (DialogueNode node : nodes.values()) {
            for (DialogueOption option : node.options()) {
                if (option.leadsSomewhere() && !nodes.containsKey(option.goTo())) {
                    throw new IllegalStateException(where + ": '" + id + "' node '" + node.id()
                            + "' offers \"" + option.text() + "\", which leads to '"
                            + option.goTo() + "' - and there is no such node.");
                }
            }
        }
    }

    /**
     * Content that was written and can never be seen is a mistake every time:
     * either a typo in somebody's "goto", or a branch whose way in was deleted.
     * Nothing else in the game would ever mention it.
     */
    private static void checkEveryNodeIsReachable(
            Map<String, DialogueNode> nodes, String startId, String where, String id) {
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        seen.add(startId);
        queue.add(startId);
        while (!queue.isEmpty()) {
            for (DialogueOption option : nodes.get(queue.poll()).options()) {
                if (option.leadsSomewhere() && seen.add(option.goTo())) {
                    queue.add(option.goTo());
                }
            }
        }
        for (String nodeId : nodes.keySet()) {
            if (!seen.contains(nodeId)) {
                throw new IllegalStateException(where + ": '" + id + "' node '" + nodeId
                        + "' cannot be reached from '" + startId + "', so nobody would ever see it.");
            }
        }
    }

    private static String text(JsonNode root, String field, String where) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new IllegalStateException(where + ": missing required field '" + field + "'");
        }
        return node.asText();
    }
}
