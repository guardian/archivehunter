import React from 'react';
import PropTypes from 'prop-types';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import {Tooltip} from "@material-ui/core";

class ThreeWayIcon extends React.Component {
    static propTypes = {
        iconName: PropTypes.string.isRequired,  //name of FontAwesome icon to display
        state: PropTypes.bool.isRequired,       //if true display onColour; if false display grey
        onColour: PropTypes.string.isRequired,  //colour to display for "on"
        hide: PropTypes.bool.isRequired,         //if true, hide the icon
        title: PropTypes.string                 //display this as a tooltip
    };

    render(){
        const colour = this.props.state ? this.props.onColour : "grey";
        const display = this.props.hide ? "none" : "inline";

        return <Tooltip title={this.props.title}>
            <FontAwesomeIcon icon={this.props.iconName} size="1.5x" style={{display: display, color: colour, marginLeft: "0.2em", marginRight: "0.2em"}}/>
        </Tooltip>
    }
}

export default ThreeWayIcon;