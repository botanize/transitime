<%-- For creating a route selector parameter via a jsp include.
     User can select all routes (r param then set to "") or any
     number of routes. 
     Reads in routes via API for the agency specified by the "a" param. --%>

<style type="text/css">
/* Set font for route selector. Need to use #select2-drop because of 
 * extra elements that select2 adds 
 */
#select2-drop, #routesDiv {
  font-family: sans-serif; font-size: large;
}
</style>

<script>

$.getJSON(apiUrlPrefix + "/command/routes", 
 		function(routes) {
	        // Generate list of routes for the selector
	        // For selector2 version 4.0 now can't set id to empty
	        // string because then it returns the text 'All Routes'.
	        // So need to use a blank string that can be determined
	        // to be empty when trimmed.
	 		var selectorData = [{id: ' ', text: 'All Routes'}];
	 		for (var i in routes.routes) {
	 			var route = routes.routes[i];
	 			selectorData.push({id: route.id, text: route.name})
	 		}
	 		
	 		// Configure the selector to be a select2 one that has
	 		// search capability
 			$("#route").select2({
 				placeholder: "All Routes", 				
 				data : selectorData});
	 		
	 		// Tooltips for a select2 widget don't automatically go away when 
	 		// item selected so remove the tooltip manually. This is a really 
	 		// complicated interaction between select2 and jquery UI tooltips.
	 		// First need to set the tooltip title content but getting the
	 		// originally configured title for the element.
 			var modifiedRouteElement = $( "#s2id_route" );
	 		var configuredTitle = modifiedRouteElement.attr("title");
	 		$( "#s2id_route" ).tooltip({ content: configuredTitle });
 			
	 		// Now that the title has set need to manually remove the tooltip
	 		// when a select2 item is selected. Sheesh!
 		 	$("#route").on("change", function(e) { $("#s2id_route").tooltip("close") }); 		 	
 	});
 	
</script>

    <div id="routesDiv"  class="param">
      <label for="route">Routes:</label>
      <select id="route" name="r" style="width: 380px" multiple="multiple"
      	title="Select which route you want data for. Note: selecting all routes
      		   indeed reads in data for all routes which means it could be 
      		   somewhat slow." ></select>
    </div>
    
