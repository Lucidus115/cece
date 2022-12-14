package me.lucidus.cece

import java.lang.IllegalStateException
import java.util.logging.Level
import java.util.logging.Logger

class Engine {
    private val logger = Logger.getLogger(Engine::class.java.name)

    private val entityIndex = HashMap<UInt, Archetype>()

    // list of archetypes that contain this component id
    private val componentIndex = HashMap<UInt, Archetypes>()

    private val queries = HashSet<Query>()

    private val systems = mutableListOf<(Pair<Int, EcsSystem>)>()
    private val entities = mutableListOf<Entity>()

    private val modified = mutableListOf<Entity>()

    private var isUpdating = false

    fun registerSystem(system: EcsSystem): Engine {
        return registerSystem(0, system)
    }

    fun registerSystem(priority: Int, system: EcsSystem): Engine {

        // Fetch queries
        for (query in system.queries) {
            if (queries.contains(query))
                continue
            queries.add(query)

            // Populate query
            for (ent in entities) {
                if (query.contains(ent))
                    query.entities.add(ent)
            }
        }

        val pair = Pair(priority, system)
        systems.add(pair)
        systems.sortBy { it.first }
        return this
    }

    fun update(deltaTime: Float = 0f) {
        if (isUpdating)
            throw IllegalStateException("Cannot call update more than once at a time.")
        isUpdating = true

        for ((_, system) in systems)
            system.update(deltaTime)

        // Update queries for modified entities
        val iter = modified.iterator()
        while (iter.hasNext()) {
            val ent = iter.next()

            for (query in queries) {
                if (query.contains(ent) && !query.entities.contains(ent))
                    query.entities.add(ent)
                else
                    query.entities.remove(ent)
            }
            iter.remove()
        }

        isUpdating = false
    }

    /**
     * Creates a new entity and adds them to the engine automatically
     */
    fun createEntity(): Entity {
        val ent = Entity(this)
        addEntity(ent)

        return ent
    }

    /**
     * Adds an entity to this engine.
     * @param entity
     *          An object which inherits [Entity]
     */
    fun addEntity(entity: Entity) {
        if (entities.contains(entity)) {
            logger.warning("Entity #${entity.id} has already been added")
            return
        }

        entityIndex[entity.id] = Archetype.EMPTY
        validateQuery(entity)

        entities.add(entity)

        logger.info("Entity #${entity.id} successfully added")
    }

    /**
     * Removes an entity from this engine.
     * @param entity
     *          An object which inherits [Entity]
     */
    fun removeEntity(entity: Entity) {
        if (!entities.contains(entity)) {
            logger.warning("Attempting to remove an entity that isn't registered.")
            return
        }

        val archetype = entityIndex[entity.id]!!
        for (compId in archetype.type)
            removeComponent(entity.id, compId)

        validateQuery(entity)

        entities.remove(entity)

        logger.info("Entity #${entity.id} successfully removed")
    }

    fun addComponent(entity: Entity, component: Component) {
        addComponent(entity.id, component)

        validateQuery(entity)
    }

    fun removeComponent(entity: Entity, componentClass: ComponentClass) {
        removeComponent(entity.id, ComponentType.getFor(componentClass ?: return).id)

        validateQuery(entity)
    }

    fun <T : Component?> getComponent(entity: Entity, componentClass: ComponentClass): T? {
        return getComponent(entity.id, ComponentType.getFor(componentClass ?: return null).id)
    }

    fun hasComponent(entity: Entity, componentClass: ComponentClass): Boolean {
        return hasComponent(entity.id, ComponentType.getFor(componentClass ?: return false).id)
    }

    private fun validateQuery(entity: Entity) {
        if (!modified.contains(entity))
            modified.add(entity)
    }

    private fun updateIndexes(ent: UInt, newArchetype: Archetype) {

        // Update entity archetype
        entityIndex[ent] = newArchetype

        logger.finer("New archetype id is $newArchetype.id")

        // Update component index
        for (comp in newArchetype.type) {
            if (componentIndex.containsKey(comp)) {
                val archetypes = componentIndex[comp]

                if (!archetypes!!.contains(newArchetype.id))
                    archetypes.add(newArchetype.id)
            } else
                componentIndex[comp] = mutableSetOf(newArchetype.id)
        }
    }

    private fun removeComponent(ent: UInt, componentId: UInt) {
        val archetype = entityIndex[ent] ?: return

        logger.fine("Removing component: $componentId from entity: $ent")

        // Find or create new archetype
        val newArchetype: Archetype
        if (!archetype.edges.containsKey(componentId)) {
            val type: Set<UInt> = archetype.type - componentId
            newArchetype = Archetype(type, archetype.components)

            val edge = ArchetypeEdge(archetype, newArchetype)
            archetype.edges[componentId] = edge
        } else {
            newArchetype = archetype.edges[componentId]!!.remove
        }

        // Remove component object from entity list
        if (archetype.components.containsKey(ent))
            archetype.components[ent]!!.remove(componentId)
        updateIndexes(ent, newArchetype)

        logger.fine("Successfully removed component: $componentId from entity: $ent")
    }

    private fun addComponent(ent: UInt, component: Component) {
        val archetype = entityIndex[ent] ?: return
        val componentId = ComponentType.getFor(component.javaClass).id

        logger.fine("Adding component: $componentId to entity: $ent")

        // Find or create new archetype
        val newArchetype: Archetype
        if (!archetype.edges.containsKey(componentId)) {
            val type: Set<UInt> = archetype.type + componentId

            newArchetype = Archetype(type, archetype.components)

            val edge = ArchetypeEdge(newArchetype, archetype)
            archetype.edges[componentId] = edge
        } else {
            newArchetype = archetype.edges[componentId]!!.add
        }

        // Add component object to entity list
        if (newArchetype.components.containsKey(ent))
            newArchetype.components[ent]!![componentId] = component
        else
            newArchetype.components[ent] = mutableMapOf(Pair(componentId, component))
        updateIndexes(ent, newArchetype)

        logger.fine("Successfully added component: $componentId to entity: $ent")
    }

    @Suppress("unchecked_cast")
    private fun <T : Component?> getComponent(ent: UInt, componentId: UInt): T? {
        if (!hasComponent(ent, componentId))
            return null

        val archetype = entityIndex[ent]
        return archetype!!.components[ent]!![componentId] as T
    }

    private fun hasComponent(ent: UInt, componentId: UInt): Boolean {
        val archetype = entityIndex[ent]
        val archetypes = componentIndex[componentId]

        return archetypes?.contains(archetype?.id) ?: false
    }

    init {
        logger.level = Level.WARNING

//        val handler = ConsoleHandler()
//
//        handler.level = Level.FINER
//        logger.addHandler(handler)
    }
}

typealias Archetypes = MutableSet<UInt>
typealias ComponentClass = Class<out Component>?