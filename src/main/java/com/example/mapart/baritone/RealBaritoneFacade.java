package com.example.mapart.baritone;

import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

public class RealBaritoneFacade implements BaritoneFacade {
    private static final String BARITONE_API = "baritone.api.BaritoneAPI";
    private static final String GOAL_INTERFACE = "baritone.api.pathing.goals.Goal";
    private static final String GOAL_BLOCK = "baritone.api.pathing.goals.GoalBlock";
    private static final String GOAL_NEAR = "baritone.api.pathing.goals.GoalNear";
    private static final String GOAL_GET_TO_BLOCK = "baritone.api.pathing.goals.GoalGetToBlock";

    private GoalRequest lastIssuedGoal;
    private GoalRequest pausedGoal;

    // Store original settings to restore when clearing constraints
    private Boolean originalAllowParkour;
    private Boolean originalAllowParkourPlace;
    private Boolean originalAllowJumpAt256;
    private Boolean originalAllowDescend;
    private Boolean originalAllowDownward;
    private Integer originalMaxFallHeightNoWater;
    private Integer originalMaxFallHeightBucket;
    private Boolean originalAllowDiagonalDescend;

    @Override
    public synchronized CommandResult goTo(BlockPos target) {
        return sendGoal(new GoalRequest(target.toImmutable(), 0));
    }

    @Override
    public synchronized CommandResult goNear(BlockPos target, int range) {
        if (range < 0) {
            return CommandResult.failure("Range must be >= 0.");
        }
        return sendGoal(new GoalRequest(target.toImmutable(), range));
    }

    @Override
    public synchronized CommandResult pause() {
        if (!isBusy()) {
            return CommandResult.success("No active Baritone movement to pause.");
        }

        CommandResult cancelResult = cancelInternal();
        if (!cancelResult.success()) {
            return cancelResult;
        }

        pausedGoal = lastIssuedGoal;
        return CommandResult.success("Paused active Baritone movement.");
    }

    @Override
    public synchronized CommandResult resume() {
        if (pausedGoal == null) {
            return CommandResult.failure("No paused Baritone movement to resume.");
        }

        return sendGoal(pausedGoal);
    }

    @Override
    public synchronized CommandResult cancel() {
        CommandResult result = cancelInternal();
        if (result.success()) {
            pausedGoal = null;
        }
        return result;
    }

    @Override
    public boolean isBusy() {
        try {
            Object baritone = getPrimaryBaritone();
            Object pathingBehavior = invoke(baritone, "getPathingBehavior");
            Object isPathing = invoke(pathingBehavior, "isPathing");
            if (isPathing instanceof Boolean pathing) {
                return pathing;
            }

            Object customGoalProcess = invoke(baritone, "getCustomGoalProcess");
            Object isActive = invoke(customGoalProcess, "isActive");
            return isActive instanceof Boolean active && active;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return false;
        }
    }

    private CommandResult cancelInternal() {
        try {
            Object baritone = getPrimaryBaritone();
            Object customGoalProcess = invoke(baritone, "getCustomGoalProcess");
            invoke(customGoalProcess, "onLostControl");
            return CommandResult.success("Cancelled active Baritone movement.");
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return CommandResult.failure("Failed to cancel Baritone movement: " + exception.getMessage());
        }
    }

    private CommandResult sendGoal(GoalRequest request) {
        Objects.requireNonNull(request, "request");

        try {
            Object baritone = getPrimaryBaritone();
            Object customGoalProcess = invoke(baritone, "getCustomGoalProcess");
            Object goal = request.range() == 0 ? createGoalBlock(request.target()) : createGoalNear(request.target(), request.range());
            Class<?> goalClass = Class.forName(GOAL_INTERFACE);
            Method setGoalAndPath = customGoalProcess.getClass().getMethod("setGoalAndPath", goalClass);
            setGoalAndPath.invoke(customGoalProcess, goal);
            String suffix = request.range() == 0 ? "" : " within range " + request.range();
            lastIssuedGoal = request;
            pausedGoal = null;
            return CommandResult.success("Pathing to " + request.target().toShortString() + suffix + ".");
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return CommandResult.failure("Failed to issue Baritone movement request: " + friendlyBaritoneFailure(exception));
        }
    }

    private String friendlyBaritoneFailure(Throwable throwable) {
        Throwable root = unwrap(throwable);
        if (root instanceof ClassNotFoundException || root instanceof NoSuchMethodException || root instanceof NoSuchMethodError) {
            return "Baritone API mismatch or missing runtime mod. Install a compatible baritone-api-fabric jar for this Minecraft version.";
        }
        if (root.getMessage() == null || root.getMessage().isBlank()) {
            return root.getClass().getSimpleName();
        }
        return root.getMessage();
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof InvocationTargetException ite && ite.getCause() != null) {
            current = ite.getCause();
        }
        return current;
    }

    private Object createGoalBlock(BlockPos target) throws ReflectiveOperationException {
        Class<?> goalBlockClass = Class.forName(GOAL_BLOCK);

        try {
            Constructor<?> blockPosConstructor = goalBlockClass.getConstructor(BlockPos.class);
            return blockPosConstructor.newInstance(target);
        } catch (NoSuchMethodException ignored) {
            Constructor<?> xyzConstructor = goalBlockClass.getConstructor(int.class, int.class, int.class);
            return xyzConstructor.newInstance(target.getX(), target.getY(), target.getZ());
        }
    }

    private Object createGoalNear(BlockPos target, int range) throws ReflectiveOperationException {
        Class<?> goalNearClass = Class.forName(GOAL_NEAR);
        Constructor<?> constructor = resolveGoalNearConstructor(goalNearClass);
        Class<?>[] parameterTypes = constructor.getParameterTypes();

        if (Arrays.equals(parameterTypes, new Class<?>[]{BlockPos.class, int.class})) {
            return constructor.newInstance(target, range);
        }
        if (Arrays.equals(parameterTypes, new Class<?>[]{int.class, int.class, int.class, int.class})) {
            return constructor.newInstance(target.getX(), target.getY(), target.getZ(), range);
        }
        if (Arrays.equals(parameterTypes, new Class<?>[]{int.class, int.class, int.class})) {
            return constructor.newInstance(target.getX(), target.getY(), target.getZ());
        }

        return createGoalGetToBlock(target);
    }

    static Constructor<?> resolveGoalNearConstructor(Class<?> goalNearClass) throws NoSuchMethodException {
        try {
            return goalNearClass.getConstructor(BlockPos.class, int.class);
        } catch (NoSuchMethodException ignored) {
            try {
                return goalNearClass.getConstructor(int.class, int.class, int.class, int.class);
            } catch (NoSuchMethodException ignoredAgain) {
                try {
                    return goalNearClass.getConstructor(int.class, int.class, int.class);
                } catch (NoSuchMethodException noKnownConstructor) {
                    throw new NoSuchMethodException("No supported GoalNear constructor found. Available constructors: "
                            + Arrays.stream(goalNearClass.getConstructors())
                            .map(constructor -> Arrays.stream(constructor.getParameterTypes())
                                    .map(Class::getSimpleName)
                                    .collect(Collectors.joining(", ", "(", ")")))
                            .collect(Collectors.joining(", ")));
                }
            }
        }
    }

    private Object createGoalGetToBlock(BlockPos target) throws ReflectiveOperationException {
        Class<?> goalGetToBlockClass = Class.forName(GOAL_GET_TO_BLOCK);
        try {
            Constructor<?> blockPosConstructor = goalGetToBlockClass.getConstructor(BlockPos.class);
            return blockPosConstructor.newInstance(target);
        } catch (NoSuchMethodException ignored) {
            Constructor<?> xyzConstructor = goalGetToBlockClass.getConstructor(int.class, int.class, int.class);
            return xyzConstructor.newInstance(target.getX(), target.getY(), target.getZ());
        }
    }

    private Object getPrimaryBaritone() throws ReflectiveOperationException {
        Class<?> apiClass = Class.forName(BARITONE_API);
        Method getProviderMethod = apiClass.getMethod("getProvider");
        Object provider = getProviderMethod.invoke(null);
        return invoke(provider, "getPrimaryBaritone");
    }

    private Object invoke(Object target, String methodName) throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }

    @Override
    public void applyOnPlaneConstraints() {
        try {
            Object baritone = getPrimaryBaritone();
            Object settings = invoke(baritone, "getSettings");

            // Store original values before modifying
            originalAllowParkour = getBooleanSetting(settings, "allowParkour");
            originalAllowParkourPlace = getBooleanSetting(settings, "allowParkourPlace");
            originalAllowJumpAt256 = getBooleanSetting(settings, "allowJumpAt256");
            originalAllowDescend = getBooleanSetting(settings, "allowDescend");
            originalAllowDownward = getBooleanSetting(settings, "allowDownward");
            originalMaxFallHeightNoWater = getIntSetting(settings, "maxFallHeightNoWater");
            originalMaxFallHeightBucket = getIntSetting(settings, "maxFallHeightBucket");
            originalAllowDiagonalDescend = getBooleanSetting(settings, "allowDiagonalDescend");

            // Apply on-plane constraints
            setBooleanSetting(settings, "allowParkour", false);
            setBooleanSetting(settings, "allowParkourPlace", false);
            setBooleanSetting(settings, "allowJumpAt256", false);
            setBooleanSetting(settings, "allowDescend", false);
            setBooleanSetting(settings, "allowDownward", false);
            setIntSetting(settings, "maxFallHeightNoWater", 0);
            setIntSetting(settings, "maxFallHeightBucket", 0);
            setBooleanSetting(settings, "allowDiagonalDescend", false);

            com.example.mapart.MapArtMod.LOGGER.info("[baritone-trace] Applied on-plane navigation constraints");
        } catch (ReflectiveOperationException | RuntimeException e) {
            com.example.mapart.MapArtMod.LOGGER.warn("[baritone-trace] Failed to apply on-plane constraints: {}", e.getMessage());
        }
    }

    @Override
    public void clearOnPlaneConstraints() {
        try {
            Object baritone = getPrimaryBaritone();
            Object settings = invoke(baritone, "getSettings");

            // Restore original values
            if (originalAllowParkour != null) {
                setBooleanSetting(settings, "allowParkour", originalAllowParkour);
            }
            if (originalAllowParkourPlace != null) {
                setBooleanSetting(settings, "allowParkourPlace", originalAllowParkourPlace);
            }
            if (originalAllowJumpAt256 != null) {
                setBooleanSetting(settings, "allowJumpAt256", originalAllowJumpAt256);
            }
            if (originalAllowDescend != null) {
                setBooleanSetting(settings, "allowDescend", originalAllowDescend);
            }
            if (originalAllowDownward != null) {
                setBooleanSetting(settings, "allowDownward", originalAllowDownward);
            }
            if (originalMaxFallHeightNoWater != null) {
                setIntSetting(settings, "maxFallHeightNoWater", originalMaxFallHeightNoWater);
            }
            if (originalMaxFallHeightBucket != null) {
                setIntSetting(settings, "maxFallHeightBucket", originalMaxFallHeightBucket);
            }
            if (originalAllowDiagonalDescend != null) {
                setBooleanSetting(settings, "allowDiagonalDescend", originalAllowDiagonalDescend);
            }

            // Clear stored values
            originalAllowParkour = null;
            originalAllowParkourPlace = null;
            originalAllowJumpAt256 = null;
            originalAllowDescend = null;
            originalAllowDownward = null;
            originalMaxFallHeightNoWater = null;
            originalMaxFallHeightBucket = null;
            originalAllowDiagonalDescend = null;

            com.example.mapart.MapArtMod.LOGGER.info("[baritone-trace] Cleared on-plane navigation constraints");
        } catch (ReflectiveOperationException | RuntimeException e) {
            com.example.mapart.MapArtMod.LOGGER.warn("[baritone-trace] Failed to clear on-plane constraints: {}", e.getMessage());
        }
    }

    private Boolean getBooleanSetting(Object settings, String settingName) throws ReflectiveOperationException {
        Object setting = settings.getClass().getField(settingName).get(settings);
        Object value = invoke(setting, "value");
        return value instanceof Boolean ? (Boolean) value : null;
    }

    private Integer getIntSetting(Object settings, String settingName) throws ReflectiveOperationException {
        Object setting = settings.getClass().getField(settingName).get(settings);
        Object value = invoke(setting, "value");
        return value instanceof Integer ? (Integer) value : null;
    }

    private void setBooleanSetting(Object settings, String settingName, boolean value) throws ReflectiveOperationException {
        Object setting = settings.getClass().getField(settingName).get(settings);
        Method setValue = setting.getClass().getMethod("value", Boolean.class);
        setValue.invoke(setting, value);
    }

    private void setIntSetting(Object settings, String settingName, int value) throws ReflectiveOperationException {
        Object setting = settings.getClass().getField(settingName).get(settings);
        Method setValue = setting.getClass().getMethod("value", Integer.class);
        setValue.invoke(setting, value);
    }

    private record GoalRequest(BlockPos target, int range) {
    }
}
