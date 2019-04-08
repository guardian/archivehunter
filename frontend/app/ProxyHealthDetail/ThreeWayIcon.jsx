import React from 'react';
import PropTypes from 'prop-types';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';

/*
    static renderResult(props){
        return <span>
            {InfoTable.threeWayIcon("check", props.value.haveProxy, "green", !props.value.wantProxy)}
            {InfoTable.threeWayIcon("exclamation", props.value.wantProxy, "orange", false)}
            {InfoTable.threeWayIcon("unlink", props.value.known, "black", props.value.known)}
        </span>
    }

    static threeWayIcon(iconName, state, truecolour, hide){
        const colour = state ? truecolour : "grey";
        const display = hide ? "none" : "inline";

        return <FontAwesomeIcon icon={iconName}  size="1.5x" style={{display: display, color: colour, marginLeft: "1em", marginRight: "1em"}}/>
    }
 */
class ThreeWayIcon extends React.Component {
    static propTypes = {
        iconName: PropTypes.string.isRequired,  //name of FontAwesome icon to display
        state: PropTypes.bool.isRequired,       //if true display onColour; if false display grey
        onColour: PropTypes.string.isRequired,  //colour to display for "on"
        hide: PropTypes.bool.isRequired         //if true, hide the icon
    };

    render(){
        const colour = this.props.state ? this.props.onColour : "grey";
        const display = this.props.hide ? "none" : "inline";

        return <FontAwesomeIcon icon={this.props.iconName} size="1.5x" style={{display: display, color: colour, marginLeft: "0.2em", marginRight: "0.2em"}}/>
    }
}

export default ThreeWayIcon;