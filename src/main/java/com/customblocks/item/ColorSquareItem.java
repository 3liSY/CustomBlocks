public int findColorVariant() {
    int variant = -1;
    int slot = 0;
    while (slot < getInventory().size()) {
        if (getInventory().get(slot).getColor() == this.getColor()) {
            variant = slot;
            break;
        }
        slot++;
    }
    return variant;
}

}