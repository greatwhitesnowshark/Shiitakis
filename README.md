# shitakis
MySQL - An ORM-style implementation that utilizes just enough reflection to make your life much easier. 
Automatically loads/updates/saves with high performance so you can focus on writing cleaner, faster code and finishing your projects.

You'll have to read the comments in the code, but if you start from the main method it will be easy to follow and each step is explained in detail.

I insist that whoever makes use of this code takes the short while in order to fully understand it! If you are not sure how to begin, start by building and 
running the app, making changes to the Account example until you fully understand the processes underneath - before using this code in your own projects.

Extra notes from me -

It is important to be aware of the extra memory you're deciding to use by adopting this system.

In essence, you're going to be storing in every object two additional HashMaps -
  1) A full map which contains the object's saveable/transient fields and their initial or last updated values
  2) An empty map which contains the changes made to any of the fields during the object's lifetime
  
When an object is loaded, map #1 gets filled up with all of the object's saveable fields & their initial values that were loaded.

When your saveable fields are changed during the object's lifetime, you do not have to do a single thing in order for them to save! It's better than magic.
  - pAccount.Update(); 
  // compares changed fields' values against their last updated value and executes an optimized SQL query saving only the changed column


Ideal placement for the Update() - should be called in one of two places:
  1) When the object is destroyed (this is the easiest way to ensure that you never lose any data, since when the object goes away it is flushed to the DB)
  2) In a recurring timer update function, which would be useful for updating and saving your data at more frequent intervals (recommend at least every 30m)


It's recommended to call `pAccount`.Update() more than just at the end of an objects life-cycle. For example, if your host's computer catches fire (yup...)
you would have a hard time saving the cached data because the CPU would become unresponsive..
Basically, you just won't be able to rely on just saving the values at the end in every situation so you should call Update() in a recurring call too.
This will have the added benefit of keeping the internal object caches smaller because the maps will clear as often as they can for you.


Once you have understood and are using this code successfully, you will find that suddenly you no longer have to write any code for comparing changed values
with flags before composing your SQL queries, you will no longer have to write any code for loading your object data, and you will no longer have to write
any code to ensure that your data is always going to save (you will never accidentally forget to update a column in some method because it does it for you).

It can be quite useful, but not recommended for very large projects or projects where keeping memory overhead low is a priority.
