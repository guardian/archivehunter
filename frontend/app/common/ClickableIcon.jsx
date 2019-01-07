import React from 'react';
import PropTypes from 'prop-types';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';

class ClickableIcon extends React.Component {
    static propTypes = {
        onClick: PropTypes.func.isRequired,
        icon: PropTypes.string.isRequired,
        hoveredClass: PropTypes.string,
        unHoveredClass: PropTypes.string,
        style: PropTypes.object
    };

    constructor(props){
        super(props);

        this.state = {
            hovered: false
        }
    }

    render(){
        const hoveredClass = this.props.hoveredClass ? this.props.hoveredClass : "clickable-hovered";
        const unHoveredClass = this.props.unHoveredClass ? this.props.unHoveredClass : "clickable-unhovered";

        return <FontAwesomeIcon icon={this.props.icon} onClick={this.props.onClick}
                                className={this.state.hovered ? hoveredClass : unHoveredClass}
                                onMouseOver={evt=>this.setState({hovered: true})}
                                onMouseOut={evt=>this.setState({hovered: false})}
        />
    }
}

export default ClickableIcon;