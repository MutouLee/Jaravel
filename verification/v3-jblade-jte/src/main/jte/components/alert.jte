@param gg.jte.Content slot
@param String type = "info"
@param String heading = null
@param gg.jte.Content footer = null
<div class="alert alert-${type}">
  @if(heading != null)<strong>${heading}</strong>@endif
  ${slot}
  @if(footer != null)<footer>${footer}</footer>@endif
</div>
