CustomBlocks Cloud Vault

This folder is a ready-to-deploy Cloudflare Worker for cross-server block sharing.

What this does:
- Server A uploads shared blocks here.
- Server B downloads shared blocks from here.
- Your Minecraft account is not linked to anything.
- The only "account" part is logging into Cloudflare so this Worker belongs to you.

Simple setup:

1. Make a free Cloudflare account:
   https://dash.cloudflare.com/sign-up

2. Install Node.js if you do not already have it:
   https://nodejs.org/

3. Open PowerShell in this folder:
   `C:\Users\66664\OneDrive\Desktop\Coding\CustomBlockss\cloud-vault-worker`

4. Log into Cloudflare:
   `npx wrangler login`

5. Create the storage box:
   `npx wrangler kv namespace create BLOCKS`

6. Wrangler will print a long ID.
   Open `wrangler.jsonc`
   Replace `PASTE_KV_ID_HERE` with that ID.

7. Deploy the Worker:
   `npx wrangler deploy`

8. Wrangler will print a URL like:
   `https://cb-cloud-vault.<your-subdomain>.workers.dev`

9. Put that URL into your mod config:
   `config/customblocks/config.json`

10. Set:
   `"cloudShareEnabled": true`
   `"cloudShareUrl": "https://cb-cloud-vault.<your-subdomain>.workers.dev"`

11. Restart the server.

When it is working:
- Share a block on Server A
- Use the same code on Server B
- It should import from the Cloud Vault automatically

If you get stuck:
- Send me a screenshot or the exact text from the terminal
- I can tell you the next single step
