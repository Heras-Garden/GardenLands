# How GardenLands Works

GardenLands reads shared claim and property data through GardenCore, then adds the player-facing rules for ownership, access, housing, and rentals.

## Claim hierarchy

The intended hierarchy is:

`Territory -> City -> District -> Property -> Building -> Apartment`

Smaller child claims can have their own owner and access rules while remaining inside a larger administrative area.

## Container locks

Supported containers include regular chests, trapped chests, copper chests, and barrels. Each container can override open, insert, take, and break access. A separate mob setting controls copper golems and other item-transporting mobs.

Registered mailboxes have automatic access rules and cannot be manually opened to everyone.

## Apartments

The apartment room sign uses only the unit label, such as `5A`. The apartment inherits its road and street number from the parent building or property address. A matching mailbox sign links that unit to a single chest, copper chest, or barrel.

A mailbox cannot be expanded into a double chest.