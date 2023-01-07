/*
 * Copyright 2020-2022 Siphalor
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.siphalor.amecs.impl;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import de.siphalor.amecs.api.KeyBindingUtils;
import de.siphalor.amecs.api.KeyModifier;
import de.siphalor.amecs.api.PriorityKeyBinding;
import de.siphalor.amecs.impl.duck.IKeyBinding;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.client.util.InputUtil;

@Environment(EnvType.CLIENT)
public class KeyBindingManager {
	// split it in two maps because it is ways faster to only stream the map with the objects we need
	// rather than streaming all and throwing out a bunch every time
	public static Map<InputUtil.KeyCode, List<KeyBinding>> keysById = new HashMap<>();
	public static Map<InputUtil.KeyCode, List<KeyBinding>> keysById_priority = new HashMap<>();
	/**
	 *
	 * @param keysById_map
	 * @param keyBinding
	 * @return whether the keyBinding was removed. It is not removed if it was not contained
	 */
	private static boolean removeKeyBindingFromListFromMap(Map<InputUtil.KeyCode, List<KeyBinding>> keysById_map, KeyBinding keyBinding) {
		// we need to get the backing list to remove elements thus we can not use any of the other methods that return streams
		InputUtil.KeyCode keyCode = ((IKeyBinding) keyBinding).amecs$getBoundKey();
		List<KeyBinding> keyBindings = keysById_map.get(keyCode);
		if (keyBindings == null) {
			return false;
		}
		boolean removed = false;
		// while loop to ensure that we remove all equal KeyBindings if for some reason there should be duplicates
		while (keyBindings.remove(keyBinding)) {
			removed = true;
		}
		return removed;
	}

	/**
	 *
	 * @param keysById_map
	 * @param keyBinding
	 * @return whether the keyBinding was added. It is not added if it is already contained
	 */
	private static boolean addKeyBindingToListFromMap(Map<InputUtil.KeyCode, List<KeyBinding>> keysById_map, KeyBinding keyBinding) {
		InputUtil.KeyCode keyCode = ((IKeyBinding) keyBinding).amecs$getBoundKey();
		List<KeyBinding> keyBindings = keysById_map.get(keyCode);
		if (keyBindings == null) {
			keyBindings = new ArrayList<>();
			keysById_map.put(keyCode, keyBindings);
		}
		if (keyBindings.contains(keyBinding)) {
			return false;
		}
		keyBindings.add(keyBinding);
		return true;
	}

	/**
	 *
	 * @param keyBinding
	 * @return whether the keyBinding was added. It is not added if it is already contained
	 */
	public static boolean register(KeyBinding keyBinding) {
		if (keyBinding instanceof PriorityKeyBinding) {
			return addKeyBindingToListFromMap(keysById_priority, keyBinding);
		} else {
			return addKeyBindingToListFromMap(keysById, keyBinding);
		}
	}

	public static Stream<KeyBinding> getMatchingKeyBindings(InputUtil.KeyCode keyCode, boolean priority) {
		List<KeyBinding> keyBindingList = (priority ? keysById_priority : keysById).get(keyCode);
		if (keyBindingList == null)
			return Stream.empty();
		// this looks not right: If you have a kb: alt + y and shift + alt + y and you press shift + alt + y both will be triggered
		// Correction: It works as it should. Leaving this comments for future readers
		Stream<KeyBinding> result = keyBindingList.stream().filter(keyBinding -> ((IKeyBinding) keyBinding).amecs$getKeyModifiers().isPressed());
		List<KeyBinding> keyBindings = result.collect(Collectors.toList());
		if (keyBindings.isEmpty())
			return keyBindingList.stream().filter(keyBinding -> ((IKeyBinding) keyBinding).amecs$getKeyModifiers().isUnset());
		return keyBindings.stream();
	}

	public static void onKeyPressed(InputUtil.KeyCode keyCode) {
		boolean nmuk = FabricLoader.getInstance().isModLoaded("nmuk");
		getMatchingKeyBindings(keyCode, false).forEach(keyBinding -> {
			((IKeyBinding) keyBinding).amecs$incrementTimesPressed();
			if (nmuk) {
				KeyBinding parent = NMUKProxy.getParent(keyBinding);
				if (parent != null) {
					((IKeyBinding) parent).amecs$incrementTimesPressed();
				}
			}
		});
	}

	private static Stream<KeyBinding> getKeyBindingsFromMap(Map<InputUtil.KeyCode, List<KeyBinding>> keysById_map) {
		return keysById_map.values().stream().flatMap(Collection::stream);
	}

	private static void forEachKeyBinding(Consumer<KeyBinding> consumer) {
		getKeyBindingsFromMap(keysById_priority).forEach(consumer);
		getKeyBindingsFromMap(keysById).forEach(consumer);
	}

	private static void forEachKeyBindingWithKey(InputUtil.KeyCode key, Consumer<KeyBinding> consumer) {
		getMatchingKeyBindings(key, true).forEach(consumer);
		getMatchingKeyBindings(key, false).forEach(consumer);
	}

	public static void updatePressedStates() {
		long windowHandle = MinecraftClient.getInstance().window.getHandle();
		forEachKeyBinding(keyBinding -> {
			InputUtil.KeyCode key = ((IKeyBinding) keyBinding).amecs$getBoundKey();
			boolean pressed = !keyBinding.isNotBound() && key.getCategory() == InputUtil.Type.KEYSYM && InputUtil.isKeyPressed(windowHandle, key.getKeyCode());
			((IKeyBinding) keyBinding).amecs$setPressed(pressed);
		});
	}

	/**
	 *
	 * @param keyBinding
	 * @return whether the keyBinding was removed. It is not removed if it was not contained
	 */
	public static boolean unregister(KeyBinding keyBinding) {
		if (keyBinding == null) {
			return false;
		}
		// do not rebuild the entrie map if we do not have to
		// KeyBinding.updateKeysByCode();
		// instead
		boolean removed = false;
		removed |= removeKeyBindingFromListFromMap(keysById, keyBinding);
		removed |= removeKeyBindingFromListFromMap(keysById_priority, keyBinding);
		return removed;
	}

	public static void updateKeysByCode() {
		keysById.clear();
		keysById_priority.clear();
		KeyBindingUtils.getIdToKeyBindingMap().values().forEach(KeyBindingManager::register);
	}

	public static void unpressAll() {
		KeyBindingUtils.getIdToKeyBindingMap().values().forEach(keyBinding -> ((IKeyBinding) keyBinding).amecs$reset());
	}

	public static boolean onKeyPressedPriority(InputUtil.KeyCode keyCode) {
		// because streams do evaluation lazy this code does only call onPressedPriority on so many keyBinding until one returns true
		// Or if no one returns true all are called and an empty optional is returned
		Optional<KeyBinding> keyBindings = getMatchingKeyBindings(keyCode, true).filter(keyBinding -> ((PriorityKeyBinding) keyBinding).onPressedPriority()).findFirst();
		return keyBindings.isPresent();
	}

	public static void setKeyPressed(InputUtil.KeyCode keyCode, boolean pressed) {
		AmecsAPI.CURRENT_MODIFIERS.set(KeyModifier.fromKeyCode(keyCode.getKeyCode()), pressed);

		forEachKeyBindingWithKey(keyCode, keyBinding -> ((IKeyBinding) keyBinding).amecs$setPressed(pressed));
	}
}
