package com.example.mapart.baritone;

import net.minecraft.util.math.BlockPos;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Objects;

public class RealBaritoneFacade implements BaritoneFacade {
    private static final String BARITONE_API = "baritone.api.BaritoneAPI";
    private static final String GOAL_INTERFACE = "baritone.api.pathing.goals.Goal";
    private static final String GOAL_BLOCK = "baritone.api.pathing.goals.GoalBlock";
    private static final String GOAL_NEAR = "baritone.api.pathing.goals.GoalNear";

    @Override
    public CommandResult goTo(BlockPos target) {
        return sendGoal(target, 0);
    }

    @Override
    public CommandResult goNear(BlockPos target, int range) {
        if (range < 0) {
            return CommandResult.failure("Range must be >= 0.");
        }
        return sendGoal(target, range);
    }

    @Override
    public CommandResult cancel() {
        try {
            Object baritone = getPrimaryBaritone();
            Object customGoalProcess = invoke(baritone, "getCustomGoalProcess");
            invoke(customGoalProcess, "onLostControl");
            return CommandResult.success("Cancelled active Baritone movement.");
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return CommandResult.failure("Failed to cancel Baritone movement: " + exception.getMessage());
        }
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

    private CommandResult sendGoal(BlockPos target, int range) {
        Objects.requireNonNull(target, "target");

        try {
            Object baritone = getPrimaryBaritone();
            Object customGoalProcess = invoke(baritone, "getCustomGoalProcess");
            Object goal = range == 0 ? createGoalBlock(target) : createGoalNear(target, range);
            Class<?> goalClass = Class.forName(GOAL_INTERFACE);
            Method setGoalAndPath = customGoalProcess.getClass().getMethod("setGoalAndPath", goalClass);
            setGoalAndPath.invoke(customGoalProcess, goal);
            String suffix = range == 0 ? "" : " within range " + range;
            return CommandResult.success("Pathing to " + target.toShortString() + suffix + ".");
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return CommandResult.failure("Failed to issue Baritone movement request: " + exception.getMessage());
        }
    }

    private Object createGoalBlock(BlockPos target) throws ReflectiveOperationException {
        Class<?> goalBlockClass = Class.forName(GOAL_BLOCK);
        Constructor<?> constructor = goalBlockClass.getConstructor(int.class, int.class, int.class);
        return constructor.newInstance(target.getX(), target.getY(), target.getZ());
    }

    private Object createGoalNear(BlockPos target, int range) throws ReflectiveOperationException {
        Class<?> goalNearClass = Class.forName(GOAL_NEAR);
        Constructor<?> constructor = goalNearClass.getConstructor(int.class, int.class, int.class, int.class);
        return constructor.newInstance(target.getX(), target.getY(), target.getZ(), range);
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
}
