package com.zenith.mc.block;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenith.util.Maps;
import com.zenith.util.math.MathHelper;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import lombok.SneakyThrows;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.DataPalette;
import org.jspecify.annotations.Nullable;

import java.util.*;

import static com.zenith.Shared.OBJECT_MAPPER;

public class BlockDataManager {
    private final Int2ObjectOpenHashMap<Block> blockStateIdToBlock;
    private final Int2ObjectOpenHashMap<List<CollisionBox>> blockStateIdToCollisionBoxes;
    private final Int2ObjectOpenHashMap<List<CollisionBox>> blockStateIdToInteractionBoxes;
    private final Int2ObjectOpenHashMap<FluidState> blockStateIdToFluidState = new Int2ObjectOpenHashMap<>(100, Maps.FAST_LOAD_FACTOR);

    public BlockDataManager() {
        int blockStateIdCount = BlockRegistry.REGISTRY.getIdMap().int2ObjectEntrySet().stream()
            .map(Map.Entry::getValue)
            .map(Block::maxStateId)
            .max(Integer::compareTo)
            .orElseThrow();
        blockStateIdToBlock = new Int2ObjectOpenHashMap<>(blockStateIdCount, Maps.FAST_LOAD_FACTOR);
        blockStateIdToCollisionBoxes = new Int2ObjectOpenHashMap<>(blockStateIdCount, Maps.FAST_LOAD_FACTOR);
        blockStateIdToInteractionBoxes = new Int2ObjectOpenHashMap<>(blockStateIdCount, Maps.FAST_LOAD_FACTOR);
        try {
            init();
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SneakyThrows
    private void init() {
        for (Int2ObjectMap.Entry<Block> entry : BlockRegistry.REGISTRY.getIdMap().int2ObjectEntrySet()) {
            var block = entry.getValue();
            for (int i = block.minStateId(); i <= block.maxStateId(); i++) {
                blockStateIdToBlock.put(i, block);
            }
        }
        initShapeCache("blockCollisionShapes", blockStateIdToCollisionBoxes);
        initShapeCache("blockInteractionShapes", blockStateIdToInteractionBoxes);
        try (JsonParser fluidsParse = OBJECT_MAPPER.createParser(getClass().getResourceAsStream(
            "/mcdata/fluidStates.json"))) {
            TreeNode treeNode = fluidsParse.getCodec().readTree(fluidsParse);
            ObjectNode fluidStatesNode = (ObjectNode) treeNode;
            for (Iterator<String> it = fluidStatesNode.fieldNames(); it.hasNext(); ) {
                String stateIdString = it.next();
                int stateId = Integer.parseInt(stateIdString);
                ObjectNode fluidStateNode = (ObjectNode) fluidStatesNode.get(stateIdString);
                boolean water = fluidStateNode.get("water").asBoolean();
                boolean source = fluidStateNode.get("source").asBoolean();
                int amount = fluidStateNode.get("amount").asInt();
                boolean falling = fluidStateNode.get("falling").asBoolean();
                blockStateIdToFluidState.put(stateId, new FluidState(water, source, amount, falling));
            }
        }
        DataPalette.GLOBAL_PALETTE_BITS_PER_ENTRY = MathHelper.log2Ceil(blockStateIdToBlock.size());
    }

    @SneakyThrows
    private void initShapeCache(String name, Int2ObjectOpenHashMap<List<CollisionBox>> output) {
        try (JsonParser shapesParser = OBJECT_MAPPER.createParser(getClass().getResourceAsStream(
            "/mcdata/" + name + ".json"))) {
            final Int2ObjectOpenHashMap<List<CollisionBox>> shapeIdToCollisionBoxes = new Int2ObjectOpenHashMap<>(100);
            TreeNode node = shapesParser.getCodec().readTree(shapesParser);
            ObjectNode shapesNode = (ObjectNode) node.get("shapes");
            for (Iterator<String> it = shapesNode.fieldNames(); it.hasNext(); ) {
                String shapeIdName = it.next();
                int shapeId = Integer.parseInt(shapeIdName);
                final List<CollisionBox> collisionBoxes = new ArrayList<>(2);
                ArrayNode outerCbArray = (ArrayNode) shapesNode.get(shapeIdName);
                for (Iterator<JsonNode> it2 = outerCbArray.elements(); it2.hasNext(); ) {
                    ArrayNode innerCbArray = (ArrayNode) it2.next();
                    double[] cbArr = new double[6];
                    int i = 0;
                    for (Iterator<JsonNode> it3 = innerCbArray.elements(); it3.hasNext(); ) {
                        DoubleNode doubleNode = (DoubleNode) it3.next();
                        cbArr[i++] = doubleNode.asDouble();
                    }
                    collisionBoxes.add(new CollisionBox(cbArr[0], cbArr[3], cbArr[1], cbArr[4], cbArr[2], cbArr[5]));
                }
                shapeIdToCollisionBoxes.put(shapeId, collisionBoxes);
            }

            ObjectNode blocksNode = (ObjectNode) node.get("blocks");
            for (Iterator<String> it = blocksNode.fieldNames(); it.hasNext(); ) {
                String blockName = it.next();
                int blockId = Integer.parseInt(blockName);
                JsonNode shapeNode = blocksNode.get(blockName);
                final IntArrayList shapeIds = new IntArrayList(2);
                if (shapeNode.isInt()) {
                    int shapeId = shapeNode.asInt();
                    shapeIds.add(shapeId);
                } else if (shapeNode.isArray()) {
                    ArrayNode shapeIdArray = (ArrayNode) shapeNode;
                    for (Iterator<JsonNode> it2 = shapeIdArray.elements(); it2.hasNext(); ) {
                        int shapeId = it2.next().asInt();
                        shapeIds.add(shapeId);
                    }
                } else throw new RuntimeException("Unexpected shape node type: " + shapeNode.getNodeType());

                Block blockData = BlockRegistry.REGISTRY.get(blockId);
                for (int i = blockData.minStateId(); i <= blockData.maxStateId(); i++) {
                    int nextShapeId = shapeIds.getInt(0);
                    if (shapeIds.size() > 1)
                        nextShapeId = shapeIds.getInt(i - blockData.minStateId());
                    List<CollisionBox> collisionBoxes = shapeIdToCollisionBoxes.get(nextShapeId);
                    output.put(i, collisionBoxes);
                }
            }
        }
    }

    public @Nullable Block getBlockDataFromBlockStateId(int blockStateId) {
        Block blockData = blockStateIdToBlock.get(blockStateId);
        if (blockData == blockStateIdToBlock.defaultReturnValue()) return null;
        return blockData;
    }

    public List<CollisionBox> getCollisionBoxesFromBlockStateId(int blockStateId) {
        List<CollisionBox> collisionBoxes = blockStateIdToCollisionBoxes.get(blockStateId);
        if (collisionBoxes == blockStateIdToCollisionBoxes.defaultReturnValue()) return Collections.emptyList();
        return collisionBoxes;
    }

    public List<CollisionBox> getInteractionBoxesFromBlockStateId(int blockStateId) {
        List<CollisionBox> collisionBoxes = blockStateIdToInteractionBoxes.get(blockStateId);
        if (collisionBoxes == blockStateIdToInteractionBoxes.defaultReturnValue()) return Collections.emptyList();
        return collisionBoxes;
    }

    public List<LocalizedCollisionBox> localizeCollisionBoxes(List<CollisionBox> collisionBoxes, Block block, int x, int y, int z) {
        var offsetVec = block.offsetType().getOffsetFunction().offset(block, x, y, z);
        final List<LocalizedCollisionBox> localizedCollisionBoxes = new ArrayList<>(collisionBoxes.size());
        for (int i = 0; i < collisionBoxes.size(); i++) {
            var collisionBox = collisionBoxes.get(i);
            localizedCollisionBoxes.add(new LocalizedCollisionBox(
                collisionBox.minX() + offsetVec.getX() + x,
                collisionBox.maxX() + offsetVec.getX() + x,
                collisionBox.minY() + offsetVec.getY() + y,
                collisionBox.maxY() + offsetVec.getY() + y,
                collisionBox.minZ() + offsetVec.getZ() + z,
                collisionBox.maxZ() + offsetVec.getZ() + z,
                x, y, z
            ));
        }
        return localizedCollisionBoxes;
    }

    @Nullable
    public FluidState getFluidState(int blockStateId) {
        return blockStateIdToFluidState.get(blockStateId);
    }

    public boolean isAir(Block block) {
        return block == BlockRegistry.AIR || block == BlockRegistry.CAVE_AIR || block == BlockRegistry.VOID_AIR;
    }

    public float getBlockSlipperiness(Block block) {
        float slippy = 0.6f;
        if (block == BlockRegistry.ICE) slippy = 0.98f;
        if (block == BlockRegistry.SLIME_BLOCK) slippy = 0.8f;
        if (block == BlockRegistry.PACKED_ICE) slippy = 0.98f;
        if (block == BlockRegistry.FROSTED_ICE) slippy = 0.98f;
        if (block == BlockRegistry.BLUE_ICE) slippy = 0.989f;
        return slippy;
    }
}
