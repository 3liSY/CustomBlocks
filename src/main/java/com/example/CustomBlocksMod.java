package com.example; // Replace with actual package name

import java.util.List;
import java.util.ArrayList;

public class CustomBlocksMod {

    // Fields
    private List<String> customBlocks;

    // Constructor
    public CustomBlocksMod() {
        this.customBlocks = new ArrayList<>();
    }

    // Method to add a custom block
    public void addCustomBlock(String block) {
        customBlocks.add(block);
    }

    // Method to get all custom blocks
    public List<String> getCustomBlocks() {
        return customBlocks;
    }

    // Method to remove a custom block
    public boolean removeCustomBlock(String block) {
        return customBlocks.remove(block);
    }

    // Method to clear all custom blocks
    public void clearCustomBlocks() {
        customBlocks.clear();
    }

    // Main method for testing
    public static void main(String[] args) {
        CustomBlocksMod mod = new CustomBlocksMod();
        mod.addCustomBlock("Block1");
        mod.addCustomBlock("Block2");
        System.out.println(mod.getCustomBlocks()); // Should print [Block1, Block2]
        mod.removeCustomBlock("Block1");
        System.out.println(mod.getCustomBlocks()); // Should print [Block2]
        mod.clearCustomBlocks();
        System.out.println(mod.getCustomBlocks()); // Should print []
    }
}