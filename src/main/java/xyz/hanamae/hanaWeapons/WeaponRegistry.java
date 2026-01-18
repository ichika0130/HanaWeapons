package xyz.hanamae.hanaWeapons;

import org.bukkit.inventory.ItemStack;
import xyz.hanamae.hanaWeapons.api.AbstractWeapon;
import xyz.hanamae.hanaWeapons.greatSword.GreatSword;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class WeaponRegistry {

    private final List<AbstractWeapon> registeredWeapons = new ArrayList<>();

    public WeaponRegistry(HanaWeapons plugin, WeaponManager manager) {
        // 在这里注册所有武器
        register(new GreatSword(plugin, manager));
    }

    public void register(AbstractWeapon weapon) {
        registeredWeapons.add(weapon);
    }

    public Optional<AbstractWeapon> findWeapon(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return Optional.empty();
        
        for (AbstractWeapon weapon : registeredWeapons) {
            if (weapon.matches(item)) {
                return Optional.of(weapon);
            }
        }
        return Optional.empty();
    }
}
